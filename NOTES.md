# Pocket Foundry — notes

Launcher APK that runs the official Factorio linux-arm64 build on Android.
Findings, decisions and dead ends, newest phase last.

## Phase 0 — reconnaissance (2026-10-27)

### Phone (read over adb)

| | |
|---|---|
| Model | vivo X200 Ultra, V2454A (China ROM, OriginOS 6) |
| SoC / GPU | SM8750 Snapdragon 8 Elite / Adreno 830v2 |
| Android | 16 (SDK 36), security patch 2026-07-01 |
| Root | No: bootloader locked, verified boot green, `user` build, no `su` |
| Kernel | 6.6 GKI, 4 KB pages |
| RAM / free storage | 11 GB / 108 GB |
| GPU node | `/dev/kgsl-3d0` is `crw-rw-rw-`, so apps can open it (Turnip/freedreno KGSL) |
| System drivers | `ro.hardware.vulkan=adreno`, `ro.hardware.egl=adreno` |
| Installed | No Termux, Termux:X11 or other launchers yet |

### Mac (build host)

macOS 27.0.1 on Apple Silicon. Homebrew `android-commandlinetools` SDK
(`$ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`): platforms 35/36,
build-tools 35/36, NDK r27b (27.1.12297006), CMake. Temurin JDK 17. No Android
Studio: **decision:** build with Gradle from the command line, no IDE needed.
`readelf` comes from the NDK (`toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf`).

### Getting the game

factorio.com now ships a standalone **linux-arm64** build, so neither Steam nor
the Steam depot is needed. Logged-in owners download it from factorio.com/download
(`factorio_linux-arm64_2.1.23.tar.xz`, 1.47 GB; `factorio-space-age_linux-arm64_…`
for the expansion). The experimental-era situation (arm64 only in the Steam depot,
which needs the Steam service) no longer applies. Checksums: factorio.com/download/sha256sums/.

Local copy (outside the repo): `pocketfoundry-game/full-2.1.23/`,
sha256 `c49989f7…b58112` verified. Unpacked 2.0 GB, of which `data/` is 1.4 GB.
Layout: `bin/arm64/factorio`, `data/`, `config-path.cfg`, `doc-html/`.

### The binary: `bin/arm64/factorio` (2.1.23)

Only `file`, `readelf -h/-l/-d`, `readelf --dyn-syms` and the shipped text files were used.

- ELF64 aarch64 **PIE**, 261 MB, interpreter `/lib/ld-linux-aarch64.so.1`, "for GNU/Linux 3.7.0".
- LOAD segments are 64 KB aligned, so the binary runs on 4 KB and 16 KB page kernels.
- **NEEDED: only `libresolv.so.2`, `libm.so.6`, `libc.so.6`.** No RPATH/RUNPATH.
  No libSDL, libGL, libX11, libpulse or libvulkan link: everything graphical and audio is **dlopen'd**
  (`dlopen`/`dlsym`/`dlclose`/`dladdr` are imported). Which libraries exactly get dlopen'd → confirm at runtime in Phase 1 with `LD_DEBUG=libs`.
- **SDL is static.** `data/licenses.txt` lists **SDL 3** and **SDL_mixer 3** (plus libcurl,
  libdecor, Lua, Ogg, libbacktrace…). The dynamic symbol table **exports nothing**: all 441 entries are imports from glibc.
- **Minimum glibc: 2.38** (`fmod@GLIBC_2.38`, `__isoc23_strtol@GLIBC_2.38`, …; also 2.36 `arc4random`,
  2.35 `_dl_find_object`, 2.34 pthread-in-libc). → Debian 12 (2.36) is too old.
  **Debian 13 trixie (2.41)** or Ubuntu 24.04 (2.39) work.
- Imports worth knowing about for the Android runtime:
  - `open`/`openat`/`ioctl`/`read`/`inotify_*`/`opendir`/`readdir`: SDL3's evdev joystick path. These are
    dynamic libc imports, so LD_PRELOAD can interpose them (input option B works).
  - `shmget`/`shmat`/`shmctl`/`shmdt`: SysV shared memory, which Android doesn't have. Probably SDL's X11 XShm
    framebuffer path. Needs a shim (libandroid-shmem style) or must fail gracefully.
  - `fork`/`vfork`/`execv`/`execl`/`posix_spawnp`/`popen`: the game can start processes (restart after
    mod changes?). This matters once the game runs as `ld.so <game>`, see risks.
  - `res_nquery`/`ns_*`/`getaddrinfo`: multiplayer DNS. The runtime needs an `/etc/resolv.conf`.
  - `getpwuid`: Android app uids aren't in `/etc/passwd`. Probably harmless (HOME is used).
- `config-path.cfg`: `config-path=__PATH__executable__/../../config`, `use-system-read-write-data-directories=false`.

### SDL3 facts that change the plan

- SDL3 still reads the legacy `SDL_VIDEODRIVER` / `SDL_AUDIODRIVER` env vars (`src/SDL_hints.c`), as well as
  `SDL_VIDEO_DRIVER` / `SDL_AUDIO_DRIVER`. Set both in scripts.
- SDL3's **dynamic API**: a statically linked SDL3 checks the env var **`SDL3_DYNAMIC_API=/path/libSDL3.so`**
  and, if set, routes *every* SDL call through that library (`src/dynapi/SDL_dynapi.c`). It's an official SDL
  mechanism for exactly this case. Conditions: the loaded SDL3 must be the same version or newer (its jump table can't
  be smaller); if loading fails, SDL warns and uses the built-in copy. Games can compile it out, so **this needs a
  runtime test** (Phase 1: `LD_DEBUG=libs` + `SDL3_DYNAMIC_API` pointing at a current SDL3 build).

### Decisions

- **Input bridge: replace "A as written" with A′ (SDL3 dynamic API), with B as fallback.**
  Plain A (an LD_PRELOAD library calling `SDL_AttachVirtualJoystick`) is impossible because the game's SDL is static
  and exports nothing. A′: ship our own libSDL3.so (zlib licence) with a virtual gamepad fed from a socket, and
  load it with `SDL3_DYNAMIC_API`. That's a documented environment variable, with no patching of the game. If the game
  ignores the variable, fall back to **B**: an LD_PRELOAD fake `/dev/input/eventN` (`open`/`ioctl`/`read`/`inotify`
  are interposable, see above).
  A′ also gives us full control over SDL's video and audio backends later.
- **GPU: try freedreno GL directly first, then Zink+Turnip.** Mesa ≥ 26.0 has Adreno 8xx in both freedreno
  (Gallium GL) and Turnip (Vulkan) on KGSL, so desktop GL may not need Zink at all. lfdevs/mesa-for-android-container
  (Debian trixie arm64 builds, `MESA_LOADER_DRIVER_OVERRIDE=kgsl`) lists the Adreno 830 as supported. Measure both in Phase 1.
- **Phase 1 distro: Debian 13 trixie** (glibc floor above).
- **Dropped the demo.** I downloaded the free arm64 demo to inspect it before your copy finished, but stopped once
  the real build was available. The phone is the test machine; no Docker/VM on the Mac.
- **Repo name `pocketfoundry`** (Spanish for factory), easy to change. Game files live outside the repo
  and are gitignored.

### Risks to check later

1. **Executable path under the loader (Phase 2).** Run as `ld-linux-aarch64.so <game>`, `/proc/self/exe` is the
   loader in `nativeLibraryDir`. `__PATH__executable__` (config, read-data) may then resolve to a read-only
   app directory. Plan: pass `--config` and set `read-data`/`write-data` explicitly in `config.ini`. A self-restart
   via exec would also lose the loader, so test changing mods in Phase 2.
2. **Adreno 830 + Mesa is new.** There are open reports of Turnip WSI corruption/frozen frames on Adreno 830 under Termux:X11
   (lfdevs/termux-packages#24) and of Zink crashing in other launchers with older Mesa. Use Mesa ≥ 26.3.
3. **GL version.** On the Raspberry Pi people needed `MESA_GL_VERSION_OVERRIDE=4.3`, so the game wants GL ≥ ~4.3 (the Pi got
   transparency glitches when lying about it). Check what freedreno/Zink report on the A830.
4. **No SysV shm on Android** (see imports).
5. **Startup hang reported on Asahi**: it turned out to be a muvm problem, not the game (forum t=135928).

### Licences (checked 2026-10-27, GitHub metadata)

| Project | Licence | Use |
|---|---|---|
| Termux:X11 | GPL-3.0 | X server, if embedded → **launcher must be GPL-3.0** |
| Termux app / proot-distro | GPL-3.0 | Phase 1 only (not shipped) |
| proot | GPL-2.0 | Phase 1 only; avoid shipping it |
| Winlator | LGPL-2.1 | Reference (has its own Java X server) |
| GameNative | GPL-3.0 | Architecture reference |
| Mesa | MIT (mostly) | Bundled |
| SDL3 | zlib | Our libSDL3 for the dynamic API |
| libandroid-shmem | BSD-3-Clause | SysV shm shim |
| glibc | LGPL-2.1+ | Bundled runtime (ship sources/offer) |
| PulseAudio | LGPL-2.1+ | Bundled if used |

**Recommendation: license Pocket Foundry as GPL-3.0.** It keeps Termux:X11 and GameNative code reusable, and everything
else above is compatible with it.

## Phase 1: Termux prototype (2026-10-27)

**Result: it works.** It reaches the main menu, loads a save, and the counter is visible. Default setup: Zink on Turnip,
`MESA_VK_WSI_DEBUG=sw`, X at 1584×720 scaled to the panel. **FPS/UPS 44–60 on a fresh freeplay map, save
loaded in 18 s.** No hardware keyboard/mouse was available: the game was driven with `xdotool` over SSH,
watching the screen. Scripts and steps: `phase1/`.

Audio works: the music plays (SDL → libpulse → Termux's PulseAudio over TCP).

Game log: `Factorio 2.1.23 (build 87750, linux-arm64, full)`, video driver x11, audio pulseaudio, graphics preset
`integrated-gpuhigh`, 8363 MB "VRAM" detected, about 1.1 GB of texture atlases.

### GL paths compared (same save, 1584×720)

| Path | GL | Load | FPS/UPS | Screen |
|---|---|---|---|---|
| freedreno (`kgsl`) | 4.3 core | 203–223 s | 20–22 / 60 | clean |
| Zink + Turnip, default WSI | 4.6 core | 18 s | 40–45 / 47–49 | **black flashes** |
| Zink + Turnip, `TU_DEBUG=sysmem` | 4.6 | 18 s | 45 / 49 | black flashes |
| **Zink + Turnip, `MESA_VK_WSI_DEBUG=sw`** | 4.6 | 18 s | **44–60 / 45–60** | **clean** |

- freedreno is GPU-bound in this game: about 49 ms of GPU time per frame, 20 ms of it in light composition (Turnip: about 18–26 ms
  in total, light composition 0.6 ms). It also takes minutes to generate and upload mipmaps at startup. At native
  3168×1440 it did 20.7 FPS, the same as at half resolution, so pixel count isn't what limits it.
- Turnip's default X11 present (DRI3) shows frames before the GPU finishes them, causing flashes. This matches open reports
  for Adreno 7xx/8xx under Termux:X11 (lfdevs/mesa-for-android-container#44). freedreno has a wait-fence fix
  (that repo's PR #96), and the matching Termux:X11 fix (termux-x11#1114) is already in the 2026-10-27 nightly.
  The software WSI path copies each finished frame, which cures it at this resolution with no visible cost.
  **Phase 2:** our own display path should wait on the render fence before showing a buffer, then drop the `sw` workaround.
- `glxgears -fullscreen`: 120 FPS with vsync on both paths (the panel's 120 Hz); 268 (freedreno) and 584 (Zink, immediate) without.
  Presentation itself isn't a bottleneck.

### proot costs a lot

- UPS stays under 60 on an *empty* map. The multithreaded update tasks (transport lines, electric network) take 2–4.7 ms each
  with nothing to do, consistent with proot tracing every futex and syscall through one tracer process. On the first run
  the sprite loader used about one core out of 7 threads.
- Background helpers that went through proot (my window-fit poller, several stale copies of it) visibly lowered UPS.
  Removing proot is the main expected gain of Phase 2. Re-measure there before judging performance.

### Things the setup needed

- **vivo freezes background apps' whole cgroup** (`/sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze = 1`) even with a
  foreground service. Termux's X server, PulseAudio, sshd and the game all froze ("X server stalled") once
  Termux:X11 was in front. `am unfreeze --sticky` didn't help (it's vivo's own freezer, com.vivo.pem/abe, not AOSP's).
  **Fix: the sharedUid Termux:X11 build**, signed with the same key as the GitHub Termux build, so both share uid 10377.
  The phantom-process settings were set too, but weren't the cause. **Phase 2:** one app, and the game is a child of the
  foreground activity, so this can't happen there. But pausing in the background (Phase 5) has to be deliberate.
- **No window manager → SDL's fullscreen is ignored**; the game opens at 1920×1080 in a corner.
  `fit-window.sh` resizes it to the X screen with xdotool. **Phase 2:** the embedded X server (or a minimal WM) must do this.
- Termux:X11's `displayScale` is inverted: 200 means half resolution (1584×720), 50 doubles it.
- First launch shows an "enter your factorio.com login to download updates" dialog. I clicked "Ask me later".
  **Phase 2:** the game's updater would write into app storage; probably set `check-updates=false` and update by re-importing.
- Memory: during the first freedreno load the system had 10.5 of 11 GB in use and 5.4 GB in swap. The game's RSS was about 800 MB, and
  the GPU memory isn't counted in RSS. Watch this on bigger saves.
- Self-check via screenrecord + average luminance **missed flicker that was visible on the phone** (partial-frame
  flashes, 60 fps capture of a 120 Hz panel). Don't use it as evidence; a person watching is the test.

### SDL3 dynamic API: confirmed live

With `SDL3_DYNAMIC_API=/usr/lib/aarch64-linux-gnu/libSDL3.so.0` (Debian's 3.2.10) the game dlopens and initialises that
library, then prints *"Couldn't override SDL library. Using a newer SDL build might help…"* and falls back to its own.
So the override isn't compiled out; it only needs an SDL3 at least as new as the game's. **Input plan A′ is viable:**
build a current SDL3 with our virtual gamepad.

### What the game dlopens (LD_DEBUG=libs)

X11/xcb (libX11, libXext, libXrandr, libXi, libXcursor, libXfixes, libXss, libXrender, libxkbcommon), glvnd
(libGL.so.1 → libGLX.so.0 → libGLX_mesa.so.0 → libgallium), libvulkan.so.1 → libvulkan_freedreno.so, libpulse,
libudev (joysticks), libwayland-*/libdecor (probed). The list from this run is inflated by Debian's libSDL3 and its dependencies (pipewire, sndio, alsa).
Mesa's libgallium pulls **libLLVM (about 100 MB)**, so Phase 2 should build Mesa with only Zink and Turnip, without llvmpipe.

## Ideas for later (2026-10-27)

- A loader for your own files and drivers: manage game versions, mods and saves; a driver screen to pick bundled
  Zink/Turnip, imported Mesa builds (they must be **glibc** aarch64 builds like lfdevs' or Winlator's driver packages;
  AdrenoTools zips are bionic and can't load in the game's process), freedreno, and the present-mode workaround.
  Per-setup resolution, driver, overlay and controller layout. Design style: photos, soft gradients, glass.
- The final product is one APK with no Termux. Termux only served the Phase 1 measurement.

### Feedback on the first APK (2026-10-27)

- [x] **No cursor at startup.** Termux:X11 drew the X arrow from the moment the server started. Patch 0004 starts it
  hidden: touch keeps it hidden (it already did), and a real mouse still shows it.
- [x] **Faster unpacking.** The game import went from 162 s to **38 s**: liblzma 5.8.4 (0BSD) through JNI
  (`app/src/main/cpp`, fetched by CMake with a pinned hash) replaces xz-java, and the tar writer runs on another thread.
  A debug build's native code is unoptimised by default, so it's forced to `-O2`; that alone was 97 s → 38 s.
  The runtime's first unpack uses the same path.
- [x] **Readable logs.** Lines wrap with a hanging indent under the timestamp, timestamps are dimmed, warnings amber,
  errors red, headers amber and bold. The screen refreshes every 2 s and follows new output while you're at the bottom.

## Phase 2: launcher APK

### Step 1: no proot, from Termux (2026-10-27) ✅

`phase2/experiments/noproot.sh`: the game and Debian's libraries, started as
`ld-linux-aarch64.so.1 --library-path … factorio --config …`. No proot, no container.

**Result: FPS/UPS 60.0/60.0 (three readings), initialised in 16.6 s.** Same save, same Zink + `MESA_VK_WSI_DEBUG=sw`.
The update took 0.56 ms per tick (under proot it was 4–9.5 ms). Transport lines and the electric network took 0.19/0.18 ms (proot: 2–4.7 ms each).
proot was about 10× on the simulation.

What it took:
- **Android's seccomp filter kills Debian's glibc at startup:** `set_robust_list` → SIGSYS (found with strace;
  `glibc.pthread.rseq=0` doesn't help). proot hid this. Fix: **Termux's Android-patched glibc** (termux-pacman/glibc-packages,
  build scripts MIT, glibc LGPL; 2.44). Its patches also fake other blocked syscalls (clone3, epoll_pwait2…), emulate
  **SysV shm on ashmem** (the game imports shmget), and resolve passwd/group for Android uids. Debian's 2.41 libraries
  and the game run on it, since it's newer. **For the APK: build glibc with these patches and our own prefix** (its etc/ dir
  then gives us a `resolv.conf` for multiplayer DNS).
- **`DISPLAY` must be the socket path** (`…/tmp/.X11-unix/X0`; `unix:/path` works too). `:0` fails: Android has no `/tmp`, and
  Termux:X11 doesn't listen on an abstract socket.
- `env -i` drops Termux's bionic LD_PRELOAD. Vulkan ICD JSON and config paths rewritten to absolute paths.
- **Executable path:** the log says `Binaries path: …/usr/glibc`, so the game derives it from the loader. `--config` with
  absolute `read-data`/`write-data` works around it, as planned in Phase 0.

Next: runtime build (patched glibc, Mesa with only Zink+Turnip and no LLVM, client libraries), a minimal WM for fullscreen,
the embedded X server, and the APK shell.

### Step 2: runtime pieces (2026-10-27)

- `runtime/build.sh glibc|mesa|wm` builds in an arm64 Debian 13 container (Docker Desktop).
  - **glibc 2.44 + Termux's Android patches** (vendored in `runtime/glibc/termux-patches`, MIT, commit c2b00b9) with prefix
    `/data/data/app.pocketfoundry/files/usr`. Our one change: `system()`/`popen()` use `/system/bin/sh`.
    On the phone (from Termux) it runs Debian programs and X clients, and `id` resolves `u0_a377`.
  - **Mesa** at lfdevs commit 6f90e92 (the one tested in Phase 1): Zink + freedreno (Gallium), Turnip with kgsl/msm, X11 only,
    glvnd, **no LLVM**. The lib dir is 43 MB, against about 150 MB for the Phase 1 build that pulls libLLVM.
  - **pocketfoundry-wm** (xcb, about 150 lines): keeps every top-level window fullscreen, follows RandR resizes, and advertises EWMH fullscreen.
- Docker Desktop installed an update on first start and took several minutes to come up ("no route to host" to its VM in the
  backend log until then).
- macOS `tar` adds `LIBARCHIVE.xattr` headers that GNU tar warns about. Use `COPYFILE_DISABLE=1 tar --no-xattrs`.

**Embedding Termux:X11** (research on master e8e9847, 2026-10-27):
- The repo ships the X server as an Android library module `:lorie` (Java + AIDL + CMake, 16 git submodules: xserver,
  pixman, libx11, xkbcomp…). `APPLICATION_ID` is taken from the host app. Plan: git submodule pinned to that commit +
  our patches; include `:lorie` and `:shell-loader:stub`.
- How the server starts: `app_process … com.termux.x11.CmdEntryPoint :0` (skip `Loader`, which checks Termux's key). It
  broadcasts a binder to the app and listens on TCP 127.0.0.1:7892 for a "knock". The activity gets a socketpair; frames are
  shared through an AHardwareBuffer and drawn by `LorieView` with EGL in the app process.
- We must set `TMPDIR` (socket `$TMPDIR/.X11-unix/X0`, plus an abstract `@$TMPDIR/...`) and `XKB_CONFIG_ROOT`, and ship
  xkeyboard-config data. Fallbacks point at com.termux. Change port 7892 (device-wide, collides with Termux:X11).
- Resolution is set from the activity's SharedPreferences (`displayResolutionMode`, `displayResolutionCustom`…). The server starts
  at 1280×1024 until the activity connects. Input: `LorieView.send{Mouse,Touch,Key,Text}Event`. **The gamepad overlay** goes
  on `R.id.frame` of a `MainActivity` subclass.
- **The black flashes explained:** the server only waits on a client's Present `wait_fence`; nothing waits for the client GPU
  to finish. Turnip/Zink presenting without a fence → half-drawn buffers. Proper fix on the Mesa side (fence before present).
  Keep `MESA_VK_WSI_DEBUG=sw` until then.
- Building on macOS needs a **case-sensitive volume** (bionic `<xlocale.h>` vs libx11 `X11/Xlocale.h`) and **GNU patch**
  (Apple's patch silently skips the CMake-applied patches). Native build about 20 s for arm64. AGP 9.3.1, Gradle 9.8.2, NDK pinned 29.0.
- **Assembled runtime** (`runtime/build.sh assemble`): our glibc + our Mesa + Debian 13's X11/glvnd/Vulkan-loader
  libraries resolved with ldd + pocketfoundry-wm + xkb data. 76 MB of libraries, 4 MB of data, **12.7 MB as tar.xz**.
  Tested from Termux (`phase2/experiments/runtime-test.sh`, prefix paths overridden by env): **the game runs on it at 60/60,
  initialised in 18.4 s, and pocketfoundry-wm makes it fullscreen.** No audio libraries yet: the game retries SDL audio every 0.5 s
  and warns each time (`libasound.so.2` missing). Use `SDL_AUDIO_DRIVER=dummy` until our SDL3 brings audio.

### Step 3: the launcher APK (2026-10-27) ✅ Phase 2 criterion met

**Inside Pocket Foundry, without Termux:** import your .tar.xz → Play → main menu → "Continue phase1-test" → **FPS/UPS
60.0/60.1/60.0** (three readings), initialised in 16.2 s, driven by touch (direct touch = tap to click). Logs are readable
in the app. Factorio's TLS check to auth.factorio.com succeeds, so network, DNS (our resolv.conf) and certificates work.

How it's put together (`app/`, Kotlin, AGP 9.3.1 with built-in Kotlin, Gradle 9.8.2, `tools/build.sh`):
- `:lorie` (Termux:X11) as a git submodule at e8e9847 plus `third_party/patches/termux-x11/` applied by `tools/setup.sh`:
  load `libXlorie.so` from `$XLORIE_LIB` (we extract native libs), xkbcomp's X11 headers via `-idirafter` (**this makes it
  build on macOS's case-insensitive disk**, no special volume needed), knock port 7894 (Termux:X11 uses 7892).
- Gradle 9 needs a real directory for the implied `:shell-loader` parent → `third_party/shell-loader-parent/`.
- APK 28.7 MB: `lib/arm64-v8a/libld-linux.so` (our glibc loader; `useLegacyPackaging true` so it's extracted and
  exec-able), `libXlorie.so`, `assets/runtime.tar.xz` + `runtime.version`.
- targetSdk 34, minSdk 29. On Android 16, exec'ing `nativeLibraryDir/libld-linux.so` and `/system/bin/app_process` from the app
  works, as does the loader mapping libraries and the game from `files/`.
- Session: `app_process … com.termux.x11.CmdEntryPoint :0 -ac` (CLASSPATH = our APK, TMPDIR = files/usr/tmp,
  XKB_CONFIG_ROOT), then pocketfoundry-wm and the game through the loader with a clean environment. Then Termux:X11's
  `MainActivity` (launcher intent filter removed by manifest merge) shows it. Display defaults are written once to its
  prefs: landscape, fullscreen, scale 200 (half resolution), direct touch, screen stays on.
- `config.ini` must start with `; version=13`, or Factorio calls it invalid and offers a reset. We write that plus
  `[path]` on first run, and afterwards only rewrite the `read-data`/`write-data` lines.
- Our processes live in the foreground app's cgroup, so vivo's freezer leaves them alone. Termux is frozen whenever we're in front
  (its sshd stops answering; `run-as com.termux` still reads its files because it's a debug build).
- `force-stop` kills the X server and the game too (same uid), which is a clean way to reset during tests.

Known gaps (later phases):
- **No audio** (`SDL_AUDIO_DRIVER=dummy`). Plan: audio backend in our own SDL3, the same library as the gamepad (Phase 3).
- ~~Import is slow: 162 s~~ fixed, 38 s (see the feedback above).
- Launcher UI is bare; Termux:X11's own preference screen and notification texts still show. The game keeps running in
  the background, and nothing happens when the game quits (Phase 5).
- Still on the `MESA_VK_WSI_DEBUG=sw` workaround for Turnip's missing present fence.

## Phase 3: input bridge and sound (2026-10-27)

**Our own SDL 3.4.18 through `SDL3_DYNAMIC_API`.** The game accepts it (its own SDL is no newer), and every SDL
call the game makes lands in our build. We only change SDL (zlib), never the game. `runtime/sdl/`:
- `pocketfoundry/SDL_pocketfoundry.c`: two pieces, both talking to the app over Unix sockets in `files/usr/tmp`, with the protocol
  in the file's header comment:
  - **Audio driver `pocketfoundry`**: float32 stereo at 48 kHz streamed to the app, which plays it with an `AudioTrack`
    (`Bridges.kt`). Blocking writes are the clock.
  - **Virtual gamepad**: plugged in while the app's input socket is connected, unplugged when the app closes it.
    It has Xbox Series USB IDs (045e:0b12), so the game shows Xbox glyphs; its "virtual" bus never matches a USB mapping,
    so SDL uses the pad's own button order. Rumble goes back to the app → phone vibration.
- `patches/0001`: CMake option `SDL_POCKETFOUNDRY`, the driver in SDL's audio list, gamepad start/stop in joystick init/quit.
- `padtest.c` → `files/usr/bin/pocketfoundry-padtest`: prints the gamepad events SDL sees, with no game involved.
- Build gotchas: SDL only offers the virtual joystick with `SDL_HIDAPI=ON` (its HIDAPI joystick driver stays off);
  XTest headers are missing, so `SDL_X11_XTEST=OFF` (the game's SDL never loaded libXtst either); io_uring off
  (Android's syscall filter).

**Factorio ignores gamepads unless `[input] input-method=game-controller`** (choices: `keyboard-and-mouse`,
`game-controller`; it doesn't switch by itself). The launcher sets it when the config doesn't say. With it set:
D-pad moves menu focus, **Y confirms** in menus (`confirm-gui-controller=controller-y`), A opens/acts, B goes back,
the left stick walks, and hints show Xbox glyphs ("Press LB + B to start a new research"). **Touch still clicks in
controller mode**: the login dialog closed with a tap. That's a first data point for the Phase 4 touch experiment.
Factorio's full list of controller bindings is in a config.ini the game wrote on exit (`[input]`, ~3000 lines).

Debug-build helpers: `adb shell am broadcast -a app.pocketfoundry.debug.PAD --es tap south` (also `press`,
`release`, `axis left_x --ei value N`, `run pocketfoundry-padtest`); see `DebugPad.kt`.

Measured: the game's log says `[Audio] Driver:pocketfoundry … 48000` and `Game controller connected: … Pocket Foundry controller`.
In game 60.1/60.1. AudioFlinger: our track on the fast path, **0 underruns**. Latency was 141 ms while the whole
5766-frame buffer Android handed out was in use; capping the playable part at 2048 frames brought it to **25–64 ms**,
still with 0 underruns.

What didn't work:
- `SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS=1` (suspecting X keyboard focus): not the cause and not needed; the input method was.
- The login dialog ignores the pad.
- A force-stop during an autosave leaves `saves/_autosaveN.tmp.zip`, which Factorio then offers as "Continue"
  and can't open. The launcher deletes `*.tmp.zip` before starting the game. The real fix is quitting the game
  gracefully (Phase 5).

## Phase 4: on-screen controller (2026-10-27) ✅ touch test pending

`app/.../controller/`: `ControllerView` draws a glass-style Xbox layout over Termux:X11's game activity: two sticks,
D-pad, A/B/X/Y, LB/RB, LT/RT (full trigger), View/Menu, L3/R3. The toolbar at the top switches to the editor (drag to
move, pinch or −/+ to resize, opacity in 4 steps, reset, ✓ saves to SharedPreferences) or hides the controller.
Haptics: a click on press, a tick on D-pad changes. Sticks float: the base centres where the thumb lands.
`TouchRouter` wraps the activity's `Window.Callback`: fingers that land on a control are the controller's until they
lift, the rest go to the game with the controller's fingers removed from the event, so a thumb on a stick and a tap
on the game work together. `OnScreenController` attaches both from the Application's activity callbacks, with no change
to Termux:X11.

Checked with adb taps: touch outside the controls reaches the game ("Ask me later"), Y on the overlay continues the
save, holding the left stick walks the character at 60/60, and the stick re-centres on release.
In game, **Y opens the character screen** (and confirms in menus).

Played on the phone: no FPS drops and responsive, sound included. Still open: the touch test (tap the map
while in controller mode).

## Phase 5: polish (2026-10-27)

Choices: life cycle first; design the new look as a canvas before building it (art comes from the
player's own copy at runtime: `data/core/graphics/background-image.jpg` and each save's `preview.jpg`, never
shipped); no mods screen; screen and battery options; GPL-3.0 (`LICENSE`, the GNU text, identical to Termux:X11's)
and a README with screenshots.

### Life cycle ✅
- **Off screen = frozen.** The game activity stopping (Home, Back, screen off) sends SIGSTOP and its return SIGCONT
  (`OnScreenController`, `Session.pause/resume`). Measured: process state `T` while away, `S` again on return, the
  same frame, 60/60. Setting `pauseInBackground` (prefs `pocketfoundry`), on by default.
- **Quit = SIGTERM.** Factorio handles it ("Received SIGTERM, shutting down … Goodbye", 0.5–10 s) but **doesn't
  save**, so the confirmation says when the newest save was written ("Last saved 15 minutes ago"). Forced kill after 30 s.
- **When the game exits, everything goes:** X server and WM stopped, the game activity finished, and if it was on
  screen the launcher comes up (Termux:X11's activity is `singleInstance` in its own task).
- **Back in the game opens the launcher** (Termux:X11 ignored Back; a mouse's back button still right-clicks).
- **`GameService`**: a foreground service (specialUse) for the session, so Android doesn't reclaim a paused 2 GB
  game, with a notification (Playing/Paused, Quit). Swiping the game out of Recents quits it properly
  (`onTaskRemoved`), not yet tried by hand.
- **Continue** starts the newest save directly with `--load-game` (test.zip loaded straight in).
- Android's `Process` has no pid; the game's is found in `/proc` (our child whose cmdline has the game binary).
- Termux:X11 still posts its own notification (channel "Termux:X11"): goes with the redesign.

Factorio draws one frame per tick, so 60 FPS on the 120 Hz panel is the game, not us. A 60 Hz display mode while
playing should save battery (Phase 5, point 4).
- **Bug: no sound and no pad in the second game of an app run** ("Audio error: Couldn't initialize
  audio"). `Bridges.kt` bound a `LocalSocket`, passed its descriptor to `LocalServerSocket` and dropped the
  `LocalSocket`; when the garbage collector reclaimed it, its cleanup closed the listening descriptor, `accept()`
  failed and both listener threads ended. Fresh app processes hid it (no collection yet). Now the bound socket lives
  as long as the server, and a failed listener logs and rebinds. Reproduced and checked with `kill -10 <app pid>`
  (ART's SIGUSR1 forces a GC) between two games in one process.

### Screen
- **UI scale 125% by default.** 125% reads best on the phone. Factorio keeps it in config.ini
  `[interface]`: `ui-scale-mode=manual-display-points` and `custom-proportional-ui-scale=1.25`. `writeConfig` sets
  both only when `ui-scale-mode` is absent, like `input-method`, so a choice made in the game stays. Checked with
  the two lines reset to Factorio's commented defaults: the log says `UIScale: manual-display-points (125.0%)`.

### The launcher in Factorio's look (2026-10-27)
Designed first on a canvas, from factorio.com's own styles measured in Chrome: panel
#313031 in a 4 px #2E2623 frame, insets #414040 / #242324, headings #FFE6C0 in Titillium Web Black, buttons with
layered inset shadows (grey #8E8E8E, green #5EB663 to go on, orange #E39C40 pressed or chosen, red to go back,
arrow tips on dialog buttons). Drawn in code (`ui/Factorio.kt`), no files copied from the site, no Factorio logo.
- **The stage** (`assets/stage`, `Stage.kt`): a WebView behind the native panels. Welcome = Pocket Foundry's own blueprint
  (SVG). Importing = that blueprint built in 3D (three.js r160, vendored, MIT): our own models at Factorio's camera
  angle (oblique projection, sun from the north-west), each piece rising out of the paper when the real import
  passes its percent. Home/Paused/Settings = a working factory drawn on a canvas from the player's own sprites.
  The rule: inserters take a real item off the belt, so both factories are simulated (`tools/stage/*.py`,
  played back by the page; each inserter is timed to reach items no upstream one takes, or it starves forever).
- **What the WebView can reach:** its assets and the PNGs under the imported `data/base/graphics`. Everything else
  is answered 404 in `shouldInterceptRequest`: no network, no other files.
- **Bug: belt items invisible in the 3D build.** An `InstancedMesh` measures its bounds at its first draw; the
  belts start empty (every instance scaled to 0 at the origin), so the items were culled ever after.
  `frustumCulled = false`.
- Ghost outlines of round parts are empty (`EdgesGeometry` finds no edges at 40°); drawing them spammed
  "Render count is 0". Skipped now.
- **Settings that work:** resolution (Termux:X11's scale 100/200, applies at the next start), frame rate 30/60
  (below), pause in background, vibration (button haptics and rumble), controller opacity, and the layout editor opened
  over a running game.
- Termux:X11's own notification is gone (`PocketFoundryApp` overrides `LorieApp.onActivityResumed`).
- **Licences added:** three.js (MIT, `assets/stage/vendor/three/LICENSE`), Titillium Web (OFL-1.1,
  `third_party/fonts/`). Both fine inside a GPL-3.0 app.

**Testing without the phone:** an emulator sized like the vivo (`pocketfoundry-test`: 1080x2376, arm64 Android 36). The phone's
display size setting puts the phone at 600 dpi, **845x384 dp** landscape: match it with `adb shell wm density 450`
(420 dpi gives 905x411 dp and hid a cut-off Quit button). The whole launcher works there, import of the real archive included
(32 s). The game even starts and connects the gamepad, but has no window (no Zink driver on the emulator's GPU).
The stage's page can be inspected over `adb forward tcp:9335 localabstract:webview_devtools_remote_<app pid>`.
Still to check on the phone: the stage's frame rate on the Adreno, the save card's picture, 60 Hz with the game,
full resolution, and that no Termux:X11 notification appears.

### Frame rate 30/60 instead of 60/120 Hz
The panel only has 120, 90 and 60 Hz modes, so 30 can't be a display mode, and the game draws at most 60 frames a
second anyway. Factorio has no frame cap of its own (only `v-sync`), but every SDL call it makes goes through our SDL:
`SDL_GL_SwapWindow` now waits for its turn when `$POCKETFOUNDRY_FRAME_RATE` (a file the app writes) says 30
(`runtime/sdl/patches/0002`, `SDL_POCKETFOUNDRY_PaceFrame`). Read once a second, so it changes in a running game. The game
keeps 60 updates and draws fewer frames, as it already did when the GPU was the limit. The panel is 60 Hz while
playing either way. **Measured on the vivo, same save: 60.1/60.1 → 30.0/60.0 → 60.1/60.1 FPS/UPS**, switched from
the pause menu's Settings without restarting the game. Default 60.

### Launcher fixes after a look on the phone
- **Quit was cut off** in the pause menu at 384 dp of height: outer margins 24 → 16 dp top and bottom (still clear
  of the 32 dp rounded corners), buttons 44 → 40 dp; the settings insets got tighter too (their note was clipped).
- **Headings:** Titillium SemiBold in near-white instead of Black in cream (neither looked right).
- **Resolution is a drop-down** listing what the game draws at (`Settings.RESOLUTION_SCALES` 100/125/150/200 →
  3168×1440 Native … 1584×720 Default, from the panel's own size), instead of Full/Half.
- **Save picture:** Factorio's preview is 16:9, so from our wider screen it has a black band; trimmed on load.
- **Rounded corners in the game:** Factorio has no UI safe-area setting (none in config.ini) and draws its GUI and
  the world into one image, so only the whole game can be inset. ~10 dp of black frame (0.29 × the 120 px corner
  radius) would keep everything visible. Not done yet.

### Game border (not black: a softened picture from the game)
`controller/GameBorder.kt`: Settings → Border Off/Thin/Wide (0/10/20 dp; 10 dp is 0.29 × the 120 px corner radius,
just clear of it). Termux:X11's `lorieView` gets margins inside its `frame`, and an ImageView behind it shows the
newest save's picture (or `terrain/grass-1.png` from the import before any save), shrunk to 160 px wide, box-blurred
twice and darkened 50%: static, no per-frame cost. The SurfaceView punches through it, so only the frame shows. The X
screen follows the view (scaled mode) and pocketfoundry-wm refits the game: **it changes live**, Settings → Resume. Checked
on the vivo at Thin: 60.0/60.0, the objective box, hotbar and tooltips clear the corners.
- Settings regrouped to fit 384 dp: Display (Resolution, Border, Frame rate) and Game (Pause in background,
  Vibration, Controls opacity, Controls layout, "In a game" before there is one).
- **Notification icon:** our small icon is the factory, but vivo's notification center draws the app's icon from its
  own cache, still Termux:X11's from the first install. MainActivity now also carries Pocket Foundry's icon (it had
  Termux:X11's in the merged manifest), and versionCode went to 2 in case vivo keys its cache on it.
- **Still the X after a clean uninstall/install,** so not a cache. Our small icon is the factory and the notification
  carries nothing else, yet vivo draws the X in full colour, like a launcher/themed icon. Termux:X11's icon is the only
  one in the APK with PNGs per density and a monochrome layer; ours was adaptive-only. So now every Termux:X11 icon
  name (`mipmap/lorie_ic_launcher*` PNGs and adaptive XML, its layer PNGs, `drawable/ic_x11_icon`) is overridden
  with Pocket Foundry's art (`tools/icon_png.py`), and our icon has PNG fallbacks and a monochrome layer. No X left in the
  APK. To check on the phone.

### The controller in the game's look
The on-screen buttons should match the game's look. The glass circles
became the game's dark inventory slots (a light top edge, the face darkening downwards, a dark outline), orange while
held as the game marks a choice, with its controller glyphs: light blue #80CEF0 (sampled from
`data/core/graphics/icons/controller`) circled A/B/X/Y, boxed LB/LT/RB/RT, ringed L/R on the stick knobs, arrows on
the D-pad. The game's glyph PNGs are only 40 px, blurry at button size, so they're drawn as vectors in the same style
(Titillium Bold); the objective box's "press (Y)" now matches the button. Sticks sit in sunken wells; the toolbar is a
small panel of slot buttons.

### Saves outside the app
Settings → **Saves**: Export copies every save into a new `Download/Pocket Foundry/Saves <date time>` folder (MediaStore,
no permission, a snapshot each time, never overwrites); uninstalling leaves it. Import takes several files from the
system picker (vivo's lists all files; the MIME filter is only a hint), keeps the ones with Factorio level data
(`level.dat0` or `level-init.dat`), skips one already here byte for byte, renames a clash to "name (2)", and keeps
the file's date from the provider so an old save doesn't become Continue. Checked on the vivo: exported 2 saves;
imported `test.zip` and `phase1-test.zip` from the afternoon's backup (dates kept, 14:54 and 13:46), and an exported
copy of `_autosave1` was recognised and skipped. "Import again" (the game) is now "Import game" beside it.

### Release build
`tools/build.sh :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk` (27.8 MB, not debuggable, no
minification). Every build, debug too, is signed with Pocket Foundry's own key in `../pocketfoundry-keys` (PKCS12, RSA 4096,
SHA-256 `0e30…e35f`, password in `keystore.properties` there, never in the repo), so release and debug builds install
over each other and keep the imported game. Losing the key means uninstalling once. The switch from the old
debug-key install was the one uninstall: saves exported first (Settings → Saves), and also kept on the Mac in
`pocketfoundry-game/saves-backup-2026-10-27/`. A release build can't be read with `run-as`; to debug, install a debug build
over it (same key).

### Community saves (refactorio.de/saves)
Three final saves from that server's archive (downloaded to `pocketfoundry-game/refactorio-saves/`,
imported through Settings → Saves). Headers read on the Mac: all Factorio 2.0.x, no Space Age.
- **Minesweeper** (2.0.72, base only): **loads and plays.**
- **Diggy** (2.0.77; mods enable-all-feature-flags, squeak-through-2, landing-pad-remote-access, redmew-data): not
  reported yet.
- **Gridlocked** (2.0.73, 121 MB, a finished factory; mods gridlocked, deep-storage-unit, early-spidertron,
  early_construction, squeak-through-2, enable-all-feature-flags…): **crashes.** Android's exit record: the app
  process SIGKILLed (status 9) at 18:25:42 in the foreground, 0.2 s after the stage WebView's renderer was killed
  for LOW_MEMORY, so most likely out of memory, not a game error. AudioTrack stopped and restarted within 3 s at
  18:24:11, which looks like Factorio's own restart after syncing mods working through the loader (risk 1 from
  Phase 0), but that's inferred, not seen. Noted, not urgent.
  To look at later on a debug build: the game's memory while loading, whether the launcher's WebView (three.js
  scenes, kept alive behind the game) should be freed during play, lower texture settings.

### Graphics driver picker (no multiple game versions, no per-setup settings)
Settings → **Driver**: Zink + Turnip (bundled, the default), **Freedreno** (bundled: our Mesa was already built with
`gallium-drivers=zink,freedreno` and kgsl, so it's only `MESA_LOADER_DRIVER_OVERRIDE=kgsl`), and imported Turnip
builds, which run under our Zink through `VK_DRIVER_FILES` (`Drivers.kt`; Session passes the chosen driver's
environment to the game only). Import reads .tar.gz/.tar.xz/.tar/.zip (by their first bytes; .tzst refused with a
message), keeps only `libvulkan_freedreno.so`, reads its ELF: arm64, `libc.so.6` among its DT_NEEDED (a bionic
AdrenoTools build is refused: it can't load in a glibc process), and every needed library present in our runtime.
The version comes from the "Mesa x.y.z" string inside; the ICD json is written with the kept path. The intended
source: lfdevs/mesa-for-android-container's `turnip_<version>_<distro>_arm64.tar.gz` (~3.5 MB), the same project our
Mesa comes from. The choice applies when the game next starts.
Checked on the vivo (debug build over the release one, same key, data kept): our own Turnip packed lfdevs-style
imported as "Turnip 27.0.0-devel"; the game's environment pointed at it and Factorio logged *zink Vulkan 1.4 (Adreno
830v1 (MESA_TURNIP))*. Freedreno: *Adreno (TM) 830v1; driver: 4.3 (Core Profile)* through kgsl (its slow load is
Phase 1's). Remove fell back to Zink.

### Performance and memory (2026-10-27)
Development now runs on the **debug build** (same Pocket Foundry key as the release, so it installs over it with data kept;
`run-as` reads the game's files and logs again).

**The launcher's WebView is gone while the game is in front.** The stage is built in `onStart` and destroyed in
`onStop`, its renderer process terminated too (Chromium keeps an idle one otherwise; `onRenderProcessGone` returns true
so the app survives that). Playing `_autosave1`: app 327 → 271 MB RSS, the renderer's 180 MB gone; Paused rebuilds the
factory backdrop in about a second.

**Gridlocked** (refactorio's 127 MB save; 525 MB level.dat, a modded server map whose mods' entities Factorio drops at
load) now loads, in ~113 s. It holds ~7.3 GB of CPU memory (≈3 GB resident, the rest in vivo's 12 GB swap) and peaks
near 10 GB while loading; the phone's free memory bottoms out around 500 MB. The earlier crash was the app being
killed 0.2 s after the WebView renderer, so the launcher's ~200 MB on top was the tipping point.

**What Factorio's memory settings do here**, where video memory *is* the phone's RAM (main menu, kgsl's per-process
GPU figure from `dumpsys gpu --gpumem` + the game's RSS; Factorio's own "Estimated VRAM usage" agrees):

| config.ini `[graphics]` | GPU | RSS | total |
|---|---|---|---|
| defaults (`video-memory-usage=all`, `graphics-quality=high`) | 1331 MB | 839 MB | 2.17 GB |
| `video-memory-usage=low` | 751 MB | 1660 MB | 2.41 GB: sprites only move to RAM |
| `graphics-quality=medium` | 632 MB | 947 MB | **1.58 GB** |

`graphics-quality` (high/medium) isn't in the game's menus in 2.x, only in config.ini, so Pocket Foundry owns it: Settings →
Display → **Sprite quality** (default High, the game's own), written on each start. At the phone's normal zoom the two
look the same (the screen draws sprites below their native size); Medium only shows zoomed right in. On Gridlocked it
takes the GPU's 1283 MB to 598 MB, which matters because that memory can't be swapped. The load peak is the map
itself either way. The Settings note ("…changes when Factorio next starts") moved beside the title: four Display rows
leave no room under them on the phone's 384 dp.

The game's own config keys (learnt from the file it writes after Settings → Confirm) include `texture-streaming`,
`optimize-for-low-vram`, `texture-compression-level`, `max-texture-size`; its log shows `Tex.Stream.: false` even with
streaming ticked. Factorio asks for a factorio.com login on the main menu (for updates): only ever "Ask me later".

### No more `MESA_VK_WSI_DEBUG=sw` (2026-10-27)
**Why it was there:** Turnip's X11 present hands the pixmap to Termux:X11 as soon as the frame is submitted, and
KGSL's dma-bufs carry no implicit fences, so the server could show a frame the GPU was still drawing (Phase 1's
black flashes). The `sw` path waits for the GPU inside the game's own present call, reads the frame back into
shared memory and has the X server copy it into the screen: a stall on the render thread plus two CPU copies.

**The fix:** `runtime/mesa/patches/0001-x11-wait-for-the-frame-before-presenting.patch`. Mesa's X11 swapchain already
sends every frame through a present thread that can wait on the image's fence (`need_fence_wait`), but only does it
for MAILBOX. The patch makes it wait always (unless explicit sync), so the wait moves off the game's thread and the
frame goes over DRI3 untouched. Our Turnip drops `sw`; imported Turnips keep it (`Drivers.environment`), as they
don't have the patch. `build-mesa.sh` applies `patches/*.patch`.

**Measured** on Minesweeper (refactorio, 788/1000 satellites), Factorio's own FPS/UPS counter (`[debug] show-fps=always`,
three readings) and `top` per process over 30 s:

| Path | Native 3168×1440 FPS/UPS | CPU game + app + X | Default 1584×720 | CPU |
|---|---|---|---|---|
| `sw` | 58.4/58.4 | 108 + 24 + 47 ≈ 179% | 60/60 | 82 + 16 + 44 ≈ 142% |
| **patched, Termux:X11 copies on the GPU** | **60.0/60.0** | 101 + 33 + 23 ≈ **157%** | 60/60 | 94 + 22 + 12 ≈ **128%** |
| patched + `TERMUX_X11_FORCE_FLIP=1` | 60.2/60.2 | 139 + 56 + 3 ≈ 198% | 60/60 | 103 + 36 + 4 ≈ 142% |

So: Native is back at a steady 60, and 10–20% of a core less work at both sizes. **Played on it: no flicker.** Flipping (Termux:X11 shows the
game's buffer itself, zero copy; it refuses Turnip's buffers by default, "for some reason it does not work fine with
turnip") empties the X server but costs more in the app's renderer and the game, so it stays off. CPU figures from
three `top` samples are noisy (±10%); the X server's drop (44–47% → 12–23%) is the clear part.

Tools: debug builds read `files/debug.env` (`game.KEY=VALUE`, `x11.KEY=VALUE`) over the game's and the X server's
environment, to try such switches without a rebuild. Termux:X11's "frames in 5.0 seconds" counter never reaches
logcat or logs/x11.log here; Factorio's counter is the one to read.

### Renamed to Pocket Foundry (2026-10-27)
The first name carried an accent (hard to type, search and share) and was an everyday word (10,898 GitHub repos named
like it; "Pocket Foundry" had 2, and no app or game of that name turned up). Names mixing "Factorio" or "Android"
were ruled out: both are trademarks, and such a name reads like an official port. The README and descriptions say
"Factorio on Android" instead, which is plain fact and what people search for. Package `app.pocketfoundry`
(baked into the runtime's paths, so everything was rebuilt); identifiers `PocketFoundry`/`POCKETFOUNDRY`/
`pocketfoundry`; signing key in `../pocketfoundry-keys` (alias unchanged). A new package installs as a new app:
the game, saves and settings were moved over from the old one with adb.

### A furnace for the icon (2026-10-27)
The assembler with a chimney read more like a radio than a foundry. Flat vector ideas looked like clip-art next to it,
so the options were rendered the same way as the old icon and the launcher's scene (three.js, Factorio's oblique
camera, a low north-west sun, worn metal, bloom on what glows): a refined assembler, a furnace, a pouring crucible,
a stitched pocket, a belt. The furnace won: it is what a foundry is, and its glowing mouth and grille still read at
home-screen size. The foreground is that render, blueprint paper included (so it is opaque); the notification and
themed icons are the same furnace drawn flat (`drawable/ic_notification.xml`, `ic_launcher_monochrome.xml`), and
`tools/icon_png.py` now draws Termux:X11's monochrome layer from it instead of the foreground's alpha.

### Saves without a picture
Saves from servers (the refactorio ones, for instance) carry no `preview.jpg`, and the save card drew an empty frame
under the name. The frame now hides when there is no picture. The README's playing and pause screenshots were retaken
on the current build, with the Minesweeper save (a player's name in a tooltip painted out).

### Touch controls (2026-10-27)
The Xbox overlay was the weakest part: 17 buttons whose meaning you had to know (LB, RT, R3…), sitting exactly on
the game's corner HUD (minimap and info panel under RB/RT/Y/B/X/A, quickbar under the D-pad), with a right-stick
cursor that is slow on a screen you could just touch, and the game's menus driven by the D-pad. On the Switch the
pad scheme got the same complaints in handheld mode; touch there only jumps the cursor, and the Switch 2 edition
added a mouse mode.

So, **Settings → Controls: Touch** (the default) or **Gamepad** (the old controller, unchanged). Touch starts
Factorio with `input-method=keyboard-and-mouse`, Gamepad with `game-controller` (`writeConfig` now always sets it from
the setting; it applies when the game next starts, so `Session.controls` keeps the one the game was started with):
- **The screen is the mouse** (`PointerGestures`): tap = left click; drag past the touch slop = left button held from
  where the finger came down (belts and pipes in a line, items across the inventory); long press (300 ms) = right
  button held (mining; half a stack in menus), with a haptic; two fingers: spreading = wheel up (zoom in), one notch
  per ×1.18, sliding = wheel (36 dp a notch, phone direction), tapped together (< 300 ms) = right click at their
  middle. Coordinates go through Termux:X11's own view-to-X-screen mapping (`TouchInputHandler.mRenderData`, read by
  reflection; if that ever fails, touches go to Termux:X11 as before), then `LorieView.sendMouseEvent`.
- **A stick that walks** with W A S D (eight directions, 30% dead zone), floating where the thumb lands.
- **Six keys** down the right edge, Factorio's defaults sent with `LorieView.sendKeyEvent` (Android key codes, real
  presses and releases): inventory E, rotate R, pipette Q, map M, menu Esc, and a pickaxe toggle that makes every
  touch on the game a right-button hold, for clearing an area. Same slots and glyph blue as the pad.
- Layouts are saved per set (`layout-touch`, `layout`). The opacity slider left Settings for the Controls choice
  (the box had no room); the layout editor's opacity tool stays.

**Testing without the phone or the game:** `pocketfoundry-inputtest` (runtime/sdl/inputtest.c) opens a plain SDL
window on the X server and prints the mouse, wheel and keys it gets, as Factorio would through SDL. A debug build runs
it in the game's place with `test.program=pocketfoundry-inputtest` in `files/debug.env`; the emulator (arm64 AVD)
runs it fine since it needs no GPU. Two-finger gestures were sent as raw multi-touch with `sendevent` (root adb on the
emulator; contacts need `ABS_MT_PRESSURE` or Android treats them as hovering). All checked: tap, long press, drag,
the six keys, the pickaxe, the stick (D; W+A), pinch out/in (wheel ±1), two-finger scroll, two-finger tap. Gamepad
mode still comes up with the Xbox overlay and `game-controller`.

Not yet tried with the game itself (phone away): where Factorio's keyboard-and-mouse HUD sits against the stick and
the key column, how precise a fingertip is at the default zoom, and which keys are missing (shift/ctrl clicks,
shooting, picking up).
