/*
  Pocket Foundry's additions to SDL: sound, a gamepad and a frame cap from the Android app, and the game's frames for
  its FPS counter.
  The game's glibc process can't load Android's libraries, so sound and gamepad go over Unix sockets.
  Delaying buffer swaps is enough for the cap: Factorio keeps its 60 updates a second and draws fewer frames. At 60 it
  changes nothing in a game, where Factorio draws a frame an update, but menus, the map editor and a paused game draw
  as fast as they can otherwise (about 270 a second in the editor on a vivo X200 Ultra).

  Audio socket: a 12-byte header ("PFA1", then rate and channels as little-endian int32), then F32LE frames.
  Input socket: 4-byte messages: a type ('B' button, 'A' axis, 'R' rumble back to the app), an index, then a
  little-endian int16 value.
  Frames file: "PFF2", then the frames the game has swapped (a little-endian uint32 that wraps), the time of the last
  swap (CLOCK_MONOTONIC nanoseconds, a little-endian int64) and a sequence that is odd while the two change. Every
  driver's frames pass here, so the app's counter is the same whichever one draws, and the time between frames is
  exact whenever the app looks.
  These names and values are the app's too (core/runtime/Sdl.kt, AudioBridge.kt, InputBridge.kt, Settings.kt):
  SourceContractTest checks they agree.

  Performance hints: with POCKETFOUNDRY_HINTS naming libhybris-common, Android's own (ADPF, libandroid's
  APerformanceHint, Android 13 and later) get each frame's work against the frame rate's target, for the game's threads:
  how a game tells Android it needs more of the CPU, the same on every phone.
  Copyright (C) 2026 Pocket Foundry contributors. Released under the zlib licence, like SDL.
*/
#include "SDL_internal.h"

#ifdef SDL_POCKETFOUNDRY

#include "SDL_pocketfoundry.h"
#include "../audio/SDL_sysaudio.h"
#include "../joystick/SDL_joystick_c.h"

#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define AUDIO_MAGIC "PFA1"  // AudioBridge in the app checks it
#define FRAMES_MAGIC "PFF2" // FrameCounter in the app checks it
#define RECONNECT_MS 1000
#define GAME_UPS 60         // Factorio's updates a second: the frame rate when the app sets none

static int POCKETFOUNDRY_Connect(const char *path)
{
    struct sockaddr_un addr;
    int fd;

    if (!path || SDL_strlen(path) >= sizeof(addr.sun_path)) {
        return -1;
    }
    SDL_zero(addr);
    addr.sun_family = AF_UNIX;
    SDL_strlcpy(addr.sun_path, path, sizeof(addr.sun_path));

    fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return -1;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static bool POCKETFOUNDRY_SendAll(int fd, const void *data, size_t len)
{
    const Uint8 *p = (const Uint8 *)data;
    while (len > 0) {
        const ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            return false;
        }
        p += n;
        len -= (size_t)n;
    }
    return true;
}

static bool POCKETFOUNDRY_RecvAll(int fd, void *data, size_t len)
{
    Uint8 *p = (Uint8 *)data;
    while (len > 0) {
        const ssize_t n = recv(fd, p, len, 0);
        if (n < 0 && errno == EINTR) {
            continue;
        }
        if (n <= 0) {
            return false;
        }
        p += n;
        len -= (size_t)n;
    }
    return true;
}

struct SDL_PrivateAudioData
{
    int fd;
    Uint8 *mixbuf;
};

static bool POCKETFOUNDRYAUDIO_WaitDevice(SDL_AudioDevice *device)
{
    return true;  // PlayDevice blocks until the app's AudioTrack has room: that is the playback clock
}

static bool POCKETFOUNDRYAUDIO_PlayDevice(SDL_AudioDevice *device, const Uint8 *buffer, int buffer_size)
{
    return POCKETFOUNDRY_SendAll(device->hidden->fd, buffer, (size_t)buffer_size);
}

static Uint8 *POCKETFOUNDRYAUDIO_GetDeviceBuf(SDL_AudioDevice *device, int *buffer_size)
{
    return device->hidden->mixbuf;
}

static void POCKETFOUNDRYAUDIO_CloseDevice(SDL_AudioDevice *device)
{
    if (device->hidden) {
        if (device->hidden->fd >= 0) {
            close(device->hidden->fd);
        }
        SDL_free(device->hidden->mixbuf);
        SDL_free(device->hidden);
        device->hidden = NULL;
    }
}

static bool POCKETFOUNDRYAUDIO_OpenDevice(SDL_AudioDevice *device)
{
    const char *path = SDL_getenv("POCKETFOUNDRY_AUDIO");
    Uint8 header[12];
    int sndbuf;

    device->hidden = (struct SDL_PrivateAudioData *)SDL_calloc(1, sizeof(*device->hidden));
    if (!device->hidden) {
        return false;
    }
    device->hidden->fd = POCKETFOUNDRY_Connect(path);
    if (device->hidden->fd < 0) {
        return SDL_SetError("Pocket Foundry audio: can't connect to %s", path ? path : "(POCKETFOUNDRY_AUDIO unset)");
    }

    // Android's native mix format; SDL converts whatever the game plays.
    device->spec.format = SDL_AUDIO_F32LE;
    device->spec.channels = 2;
    device->spec.freq = 48000;
    SDL_UpdatedAudioDeviceFormat(device);

    // A small socket buffer keeps sound in sync with the game.
    sndbuf = device->buffer_size * 2;
    setsockopt(device->hidden->fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

    SDL_memcpy(header, AUDIO_MAGIC, 4);
    SDL_memcpy(header + 4, &device->spec.freq, 4);
    SDL_memcpy(header + 8, &device->spec.channels, 4);
    if (!POCKETFOUNDRY_SendAll(device->hidden->fd, header, sizeof(header))) {
        return SDL_SetError("Pocket Foundry audio: the app closed the connection");
    }

    device->hidden->mixbuf = (Uint8 *)SDL_malloc(device->buffer_size);
    if (!device->hidden->mixbuf) {
        return false;
    }
    SDL_memset(device->hidden->mixbuf, device->silence_value, device->buffer_size);
    return true;
}

static void POCKETFOUNDRYAUDIO_DetectDevices(SDL_AudioDevice **default_playback, SDL_AudioDevice **default_recording)
{
    *default_playback = SDL_AddAudioDevice(false, DEFAULT_PLAYBACK_DEVNAME, NULL, (void *)0x1);
}

static bool POCKETFOUNDRYAUDIO_Init(SDL_AudioDriverImpl *impl)
{
    impl->OpenDevice = POCKETFOUNDRYAUDIO_OpenDevice;
    impl->WaitDevice = POCKETFOUNDRYAUDIO_WaitDevice;
    impl->PlayDevice = POCKETFOUNDRYAUDIO_PlayDevice;
    impl->GetDeviceBuf = POCKETFOUNDRYAUDIO_GetDeviceBuf;
    impl->CloseDevice = POCKETFOUNDRYAUDIO_CloseDevice;
    impl->DetectDevices = POCKETFOUNDRYAUDIO_DetectDevices;
    return true;
}

AudioBootStrap POCKETFOUNDRYAUDIO_bootstrap = {
    .name = "pocketfoundry",
    .desc = "Pocket Foundry (Android app)",
    .init = POCKETFOUNDRYAUDIO_Init,
    .demand_only = true,  // the app sets SDL_AUDIO_DRIVER=pocketfoundry
};

static SDL_Thread *input_thread;
static SDL_Semaphore *input_wakeup;
static SDL_AtomicInt input_quitting;
static SDL_AtomicInt input_fd;  // -1 while unplugged; changed under the joystick lock

static bool SDLCALL POCKETFOUNDRY_Rumble(void *userdata, Uint16 low_frequency_rumble, Uint16 high_frequency_rumble)
{
    // SDL holds the joystick lock here, so input_fd can't be closed under us.
    const int fd = SDL_GetAtomicInt(&input_fd);
    const Uint16 strength = SDL_max(low_frequency_rumble, high_frequency_rumble) >> 1;
    const Uint8 message[4] = { 'R', 0, (Uint8)(strength & 0xFF), (Uint8)(strength >> 8) };

    if (fd < 0) {
        return SDL_SetError("Pocket Foundry gamepad is unplugged");
    }
    send(fd, message, sizeof(message), MSG_NOSIGNAL | MSG_DONTWAIT);
    return true;
}

static bool POCKETFOUNDRY_RunGamepad(int fd)
{
    SDL_VirtualJoystickDesc desc;
    SDL_JoystickID id;
    SDL_Joystick *joystick;
    Uint8 message[4];

    SDL_INIT_INTERFACE(&desc);
    desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
    desc.vendor_id = 0x045e;  // Xbox Series controller: games show Xbox button glyphs
    desc.product_id = 0x0b12;
    desc.naxes = SDL_GAMEPAD_AXIS_COUNT;
    desc.nbuttons = SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1;
    desc.axis_mask = (1u << desc.naxes) - 1;
    desc.button_mask = (1u << desc.nbuttons) - 1;
    desc.name = "Pocket Foundry controller";
    desc.Rumble = POCKETFOUNDRY_Rumble;

    id = SDL_AttachVirtualJoystick(&desc);
    if (!id) {
        SDL_LogError(SDL_LOG_CATEGORY_INPUT, "Pocket Foundry gamepad: attach failed: %s", SDL_GetError());
        return false;
    }
    joystick = SDL_OpenJoystick(id);
    if (!joystick) {
        SDL_LogError(SDL_LOG_CATEGORY_INPUT, "Pocket Foundry gamepad: open failed: %s", SDL_GetError());
    } else {
        SDL_LogInfo(SDL_LOG_CATEGORY_INPUT, "Pocket Foundry gamepad plugged in");
        SDL_LockJoysticks();
        SDL_SetAtomicInt(&input_fd, fd);
        SDL_UnlockJoysticks();

        // StopInput shuts down whatever fd it sees; checking input_quitting after setting ours closes the gap.
        while (!SDL_GetAtomicInt(&input_quitting) && POCKETFOUNDRY_RecvAll(fd, message, sizeof(message))) {
            const Sint16 value = (Sint16)(message[2] | (message[3] << 8));
            if (message[0] == 'B') {
                SDL_SetJoystickVirtualButton(joystick, message[1], value != 0);
            } else if (message[0] == 'A') {
                SDL_SetJoystickVirtualAxis(joystick, message[1], value);
            }
        }

        SDL_LockJoysticks();
        SDL_SetAtomicInt(&input_fd, -1);
        SDL_UnlockJoysticks();
        SDL_CloseJoystick(joystick);
        SDL_LogInfo(SDL_LOG_CATEGORY_INPUT, "Pocket Foundry gamepad unplugged");
    }
    SDL_DetachVirtualJoystick(id);
    return joystick != NULL;
}

static int SDLCALL POCKETFOUNDRY_InputThread(void *data)
{
    const char *path = (const char *)data;

    while (!SDL_GetAtomicInt(&input_quitting)) {
        const int fd = POCKETFOUNDRY_Connect(path);
        const bool ran = fd >= 0 && POCKETFOUNDRY_RunGamepad(fd);
        if (fd >= 0) {
            close(fd);
        }
        if (!ran) {
            SDL_WaitSemaphoreTimeout(input_wakeup, RECONNECT_MS);
        }
    }
    SDL_free(data);
    return 0;
}

void SDL_POCKETFOUNDRY_StartInput(void)
{
    const char *path = SDL_getenv("POCKETFOUNDRY_INPUT");

    if (!path || input_thread) {
        return;
    }
    SDL_SetAtomicInt(&input_quitting, 0);
    SDL_SetAtomicInt(&input_fd, -1);
    input_wakeup = SDL_CreateSemaphore(0);
    input_thread = SDL_CreateThread(POCKETFOUNDRY_InputThread, "PocketFoundryInput", SDL_strdup(path));
}

void SDL_POCKETFOUNDRY_StopInput(void)
{
    int fd;

    if (!input_thread) {
        return;
    }
    SDL_SetAtomicInt(&input_quitting, 1);
    SDL_SignalSemaphore(input_wakeup);
    SDL_LockJoysticks();
    fd = SDL_GetAtomicInt(&input_fd);
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);  // wakes the thread's recv()
    }
    SDL_UnlockJoysticks();
    SDL_WaitThread(input_thread, NULL);
    input_thread = NULL;
    SDL_DestroySemaphore(input_wakeup);
    input_wakeup = NULL;
}

static int POCKETFOUNDRY_ReadFrameRate(const char *path)
{
    char text[8];
    ssize_t n;
    int fps;
    int fd = open(path, O_RDONLY | O_CLOEXEC);

    if (fd < 0) {
        return 0;
    }
    n = read(fd, text, sizeof(text) - 1);
    close(fd);
    if (n <= 0) {
        return 0;
    }
    text[n] = '\0';
    fps = SDL_atoi(text);
    return fps > 0 ? fps : 0;
}

// The frame rate PaceFrame keeps to, 0 for none: performance hints aim at it too.
static int paced_fps;

void SDL_POCKETFOUNDRY_PaceFrame(void)
{
    static const char *path;
    static bool looked;
    int fps = paced_fps;
    static Uint64 checked, next;
    Uint64 now, interval;

    if (!looked) {
        path = SDL_getenv("POCKETFOUNDRY_FRAME_RATE");
        looked = true;
    }
    if (!path) {
        return;
    }
    now = SDL_GetTicksNS();
    if (!checked || now - checked >= SDL_NS_PER_SECOND) {
        int wanted = POCKETFOUNDRY_ReadFrameRate(path);
        if (wanted != fps) {
            SDL_LogInfo(SDL_LOG_CATEGORY_VIDEO, "Pocket Foundry frame rate: %s", wanted ? "capped" : "free");
            fps = paced_fps = wanted;
        }
        checked = now;
    }
    if (!fps) {
        next = 0;
        return;
    }
    interval = SDL_NS_PER_SECOND / fps;
    if (next > now) {
        SDL_DelayPrecise(next - now);
        now = SDL_GetTicksNS();
    }
    next = (next && now < next + interval) ? next + interval : now + interval;
}

// The frames file's layout: the app's FrameCounter reads it at these offsets.
struct FrameStamp
{
    char magic[4];
    Uint32 frames;
    Sint64 swapped_ns;
    Uint32 sequence;
    Uint32 unused;
};

void SDL_POCKETFOUNDRY_CountFrame(void)
{
    static bool looked;
    static struct FrameStamp *stamp;
    struct timespec now;

    if (!looked) {
        const char *path = SDL_getenv("POCKETFOUNDRY_FRAMES");
        looked = true;
        if (path) {
            // A file the app maps too: a few stores a frame, no system call but the clock's.
            int fd = open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600);
            if (fd >= 0) {
                void *map = ftruncate(fd, sizeof(*stamp)) == 0 ?
                    mmap(NULL, sizeof(*stamp), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0) : MAP_FAILED;
                close(fd);
                if (map != MAP_FAILED) {
                    stamp = (struct FrameStamp *)map;
                    SDL_memset(stamp, 0, sizeof(*stamp));
                    SDL_memcpy(stamp->magic, FRAMES_MAGIC, 4);
                }
            }
        }
    }
    if (stamp) {
        clock_gettime(CLOCK_MONOTONIC, &now);
        // Odd while the count and its time change, so the app never pairs one frame's count with another's time.
        __atomic_add_fetch(&stamp->sequence, 1, __ATOMIC_ACQ_REL);
        __atomic_store_n(&stamp->swapped_ns, (Sint64)now.tv_sec * 1000000000 + now.tv_nsec, __ATOMIC_RELAXED);
        __atomic_add_fetch(&stamp->frames, 1, __ATOMIC_RELAXED);
        __atomic_add_fetch(&stamp->sequence, 1, __ATOMIC_RELEASE);
    }
}

/*
 * Android's performance hints, from libandroid through libhybris: the game's glibc process can't link Android's
 * libraries, but libhybris loads them as Mesa's Turnip patch does for hardware buffers. A frame's work is its main
 * thread's time from one buffer swap's return to the next swap, waits for the display left out; reported every frame
 * against the target, it lets Android raise the clocks for the game's threads when frames run late, and lower them when
 * they're early. The NDK's own signatures (android/performance_hint.h).
 */
typedef struct APerformanceHintManager APerformanceHintManager;
typedef struct APerformanceHintSession APerformanceHintSession;

static struct
{
    APerformanceHintManager *(*get_manager)(void);
    APerformanceHintSession *(*create)(APerformanceHintManager *, const int32_t *, size_t, int64_t);
    int (*report)(APerformanceHintSession *, int64_t);
    int (*update_target)(APerformanceHintSession *, int64_t);
    int (*set_threads)(APerformanceHintSession *, const pid_t *, size_t); // Android 14
    APerformanceHintSession *session;
    int64_t target_ns;
    Uint64 work_started_ns;
    unsigned frames;
    size_t thread_count;
} hints;

#define HINT_THREADS_MAX 256
#define HINT_THREADS_EVERY 300 // frames between looks for threads Factorio started since

static Uint64 POCKETFOUNDRY_Now(void)
{
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (Uint64)now.tv_sec * SDL_NS_PER_SECOND + (Uint64)now.tv_nsec;
}

static size_t POCKETFOUNDRY_Threads(pid_t *tids, size_t most)
{
    size_t count = 0;
    struct dirent *entry;
    DIR *dir = opendir("/proc/self/task");

    if (!dir) {
        return 0;
    }
    while (count < most && (entry = readdir(dir))) {
        if (entry->d_name[0] != '.') {
            tids[count++] = (pid_t)SDL_atoi(entry->d_name);
        }
    }
    closedir(dir);
    return count;
}

static int64_t POCKETFOUNDRY_Target(void)
{
    return (int64_t)(SDL_NS_PER_SECOND / (paced_fps ? paced_fps : GAME_UPS));
}

/* The game's threads now, into the session: a list with one that has just ended is refused, and tried again later. */
static void POCKETFOUNDRY_JoinThreads(void)
{
    pid_t tids[HINT_THREADS_MAX];
    size_t count;

    if (!hints.set_threads) {
        return;
    }
    count = POCKETFOUNDRY_Threads(tids, HINT_THREADS_MAX);
    if (count != hints.thread_count && hints.set_threads(hints.session, tids, count) == 0) {
        hints.thread_count = count;
    }
}

static void POCKETFOUNDRY_OpenHints(void)
{
    const char *path = SDL_getenv("POCKETFOUNDRY_HINTS");
    void *common, *android = NULL;
    void *(*android_open)(const char *, int) = NULL;
    void *(*android_sym)(void *, const char *) = NULL;
    APerformanceHintManager *manager;
    int32_t self;

    if (!path || !*path) {
        return;
    }
    common = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (common) {
        *(void **)&android_open = dlsym(common, "android_dlopen");
        *(void **)&android_sym = dlsym(common, "android_dlsym");
    }
    if (android_open && android_sym) {
        android = android_open("libandroid.so", RTLD_NOW);
    }
    if (!android) {
        fprintf(stderr, "Pocket Foundry performance hints: no libandroid through %s\n", path);
        return;
    }
    *(void **)&hints.get_manager = android_sym(android, "APerformanceHint_getManager");
    *(void **)&hints.create = android_sym(android, "APerformanceHint_createSession");
    *(void **)&hints.report = android_sym(android, "APerformanceHint_reportActualWorkDuration");
    *(void **)&hints.update_target = android_sym(android, "APerformanceHint_updateTargetWorkDuration");
    *(void **)&hints.set_threads = android_sym(android, "APerformanceHint_setThreads");
    manager = hints.get_manager ? hints.get_manager() : NULL;
    if (!manager || !hints.create || !hints.report || !hints.update_target) {
        fprintf(stderr, "Pocket Foundry performance hints: Android has none here\n");
        return;
    }
    // This thread first, which is surely alive: Android refuses a session for a list with a thread that has just
    // ended, and Factorio starts and ends threads as it loads. The others join through setThreads.
    self = (int32_t)syscall(SYS_gettid);
    hints.target_ns = POCKETFOUNDRY_Target();
    hints.session = hints.create(manager, &self, 1, hints.target_ns);
    if (!hints.session) {
        fprintf(stderr, "Pocket Foundry performance hints: Android refused a session\n");
        return;
    }
    hints.thread_count = 1;
    POCKETFOUNDRY_JoinThreads();
    fprintf(stderr, "Pocket Foundry performance hints: on, %d threads, a frame every %lld us\n",
            (int)hints.thread_count, (long long)(hints.target_ns / 1000));
}

void SDL_POCKETFOUNDRY_FrameWorked(void)
{
    static bool opened;
    Uint64 now;
    int64_t target;

    if (!opened) {
        opened = true;
        POCKETFOUNDRY_OpenHints();
    }
    if (!hints.session || !hints.work_started_ns) {
        return;
    }
    now = POCKETFOUNDRY_Now();
    target = POCKETFOUNDRY_Target();
    if (target != hints.target_ns) {
        hints.target_ns = target;
        hints.update_target(hints.session, target);
    }
    // A frame longer than a second is a load or a pause, not a frame to speed up.
    if (now - hints.work_started_ns < SDL_NS_PER_SECOND) {
        hints.report(hints.session, (int64_t)(now - hints.work_started_ns));
    }
    if (++hints.frames >= HINT_THREADS_EVERY) {
        hints.frames = 0;
        POCKETFOUNDRY_JoinThreads();
    }
}

void SDL_POCKETFOUNDRY_FrameSwapped(void)
{
    if (hints.session) {
        hints.work_started_ns = POCKETFOUNDRY_Now();
    }
}

#endif // SDL_POCKETFOUNDRY
