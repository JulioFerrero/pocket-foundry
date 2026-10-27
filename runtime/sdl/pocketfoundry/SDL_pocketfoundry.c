/*
  Pocket Foundry's additions to SDL: sound and a gamepad that come from the Android app.

  The game runs in a glibc process that can't load Android's libraries, so both
  go over Unix sockets that the app listens on.

  Audio ($POCKETFOUNDRY_AUDIO): SDL connects when the game opens its audio device and
  sends a 12-byte header (the bytes "FAB1", then freq and channels as
  little-endian uint32), then interleaved float32 samples. Writes block when the
  app's AudioTrack is full; that is the playback clock.

  Gamepad ($POCKETFOUNDRY_INPUT): while SDL is connected, a virtual Xbox-style gamepad
  is plugged in; when the app closes the connection it's unplugged. The app sends
  4-byte messages {kind, index, int16 value (little-endian)}: kind 'B' sets
  button `index` (SDL_GamepadButton) to value != 0, kind 'A' sets axis `index`
  (SDL_GamepadAxis) to value. SDL sends {'R', 0, int16 strength} for rumble,
  strength 0..32767, 0 = stop.

  Frame rate ($POCKETFOUNDRY_FRAME_RATE): a file holding the frames per second the
  player chose. Below 60, each buffer swap waits for its turn: the game keeps
  its 60 updates a second and draws fewer frames, as it does when the GPU is
  slow. Read once a second, so a change reaches a running game.

  Copyright (C) 2026 Pocket Foundry contributors. Released under the zlib licence, like SDL.
*/
#include "SDL_internal.h"

#ifdef SDL_POCKETFOUNDRY

#include "SDL_pocketfoundry.h"
#include "../audio/SDL_sysaudio.h"
#include "../joystick/SDL_joystick_c.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

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

// Audio

struct SDL_PrivateAudioData
{
    int fd;
    Uint8 *mixbuf;
};

static bool POCKETFOUNDRYAUDIO_WaitDevice(SDL_AudioDevice *device)
{
    return true;  // PlayDevice blocks until the app has room
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
    Uint8 header[12] = { 'F', 'A', 'B', '1' };
    int sndbuf;

    device->hidden = (struct SDL_PrivateAudioData *)SDL_calloc(1, sizeof(*device->hidden));
    if (!device->hidden) {
        return false;
    }
    device->hidden->fd = POCKETFOUNDRY_Connect(path);
    if (device->hidden->fd < 0) {
        return SDL_SetError("Pocket Foundry audio: can't connect to %s", path ? path : "(POCKETFOUNDRY_AUDIO unset)");
    }

    // What Android mixes natively; SDL converts whatever the game plays.
    device->spec.format = SDL_AUDIO_F32LE;
    device->spec.channels = 2;
    device->spec.freq = 48000;
    SDL_UpdatedAudioDeviceFormat(device);

    // Keep only a couple of buffers in flight so sound stays in sync with the game.
    sndbuf = device->buffer_size * 2;
    setsockopt(device->hidden->fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, sizeof(sndbuf));

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
    "pocketfoundry", "Pocket Foundry (Android app)", POCKETFOUNDRYAUDIO_Init, true, false
};

// Gamepad

static SDL_Thread *input_thread;
static SDL_Semaphore *input_wakeup;
static SDL_AtomicInt input_quitting;
static SDL_AtomicInt input_fd;  // -1 while unplugged; read and cleared under the joystick lock

static bool SDLCALL POCKETFOUNDRY_Rumble(void *userdata, Uint16 low_frequency_rumble, Uint16 high_frequency_rumble)
{
    // Called with the joystick lock held, so input_fd can't be closed under us.
    const int fd = SDL_GetAtomicInt(&input_fd);
    const Uint16 strength = SDL_max(low_frequency_rumble, high_frequency_rumble) >> 1;
    const Uint8 message[4] = { 'R', 0, (Uint8)(strength & 0xFF), (Uint8)(strength >> 8) };

    if (fd < 0) {
        return SDL_SetError("Pocket Foundry gamepad is unplugged");
    }
    send(fd, message, sizeof(message), MSG_NOSIGNAL | MSG_DONTWAIT);
    return true;
}

// Returns false when the gamepad couldn't be plugged in.
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

        // Quit shuts down whatever fd it sees; checking afterwards closes the gap.
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
        if (fd < 0) {
            SDL_WaitSemaphoreTimeout(input_wakeup, 1000);  // the app isn't listening yet
            continue;
        }
        const bool ran = POCKETFOUNDRY_RunGamepad(fd);
        close(fd);
        if (!ran) {
            SDL_WaitSemaphoreTimeout(input_wakeup, 1000);  // don't spin on a failure
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

// The frames per second in the file at [path]; 0 when there's no cap (60 or more, or no file).
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
    return (fps > 0 && fps < 60) ? fps : 0;
}

void SDL_POCKETFOUNDRY_PaceFrame(void)
{
    static const char *path;
    static bool looked;
    static int fps;
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
            fps = wanted;
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
    // Keep the cadence, but start over after a frame that came late (loading, a slow frame).
    next = (next && now < next + interval) ? next + interval : now + interval;
}

#endif // SDL_POCKETFOUNDRY
