# Pocket Foundry

**Play your own copy of Factorio on Android.** The official Linux ARM64 build runs on the phone itself: no
emulation, no streaming, no game files in the app.

|                                                                            |                                                                                      |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| ![Welcome: Pocket Foundry's blueprint](docs/screenshots/welcome.jpg)       | ![Importing: the blueprint built as the import goes](docs/screenshots/importing.jpg) |
| ![Home over a factory drawn from your own copy](docs/screenshots/home.jpg) | ![Playing with the on-screen controller](docs/screenshots/playing.jpg)               |

## Getting started

1. Install the APK from [Releases](https://github.com/JulioFerrero/pocket-foundry/releases).
2. Buy Factorio and download the **Linux (arm64) .tar.xz** from [factorio.com/download](https://factorio.com/download).
3. Open Pocket Foundry, tap **Import Factorio** and pick the file.
4. **Continue** your newest save or open the **Main menu**.

Needs Android 10 or later.

## Phones

| GPU                  | Found in                              |                               |
| -------------------- | ------------------------------------- | ----------------------------- |
| Adreno 6xx and newer | Snapdragon                            | Works                         |
| Xclipse              | Exynos (Galaxy S24, S25)              | Works with an imported driver |
| Mali                 | MediaTek, Google Tensor, older Exynos | Some, with an imported driver |
| PowerVR              | Pixel 10                              | Not yet                       |
| Adreno 5xx and older | Older Snapdragon                      | No                            |

[Phones and drivers](docs/phones.md) has the drivers to import and the phones known to work.

## Development (Fast Build with Prebuilts)

This workflow enables rapid Android-only development without requiring Docker, the Android NDK, or CMake.

Instead of compiling the Linux runtime (glibc, Mesa Turnip, SDL) inside Docker and building Termux:X11 C++ sources (~15 minutes), you can fetch official prebuilt binaries, bringing build times down to **under 5 seconds**.

### Included Prebuilt Artifacts

- **`app/src/main/jniLibs/arm64-v8a/`** — Native libraries:
  - `libXlorie.so` (Termux:X11 display server)
  - `libld-linux.so` (glibc dynamic linker)
  - `libpocketfoundry.so` (Pocket Foundry native bridge)
- **`app/src/main/assets/`** — Runtime assets:
  - `runtime.tar.xz` (minimal Linux rootfs with Mesa/Turnip drivers and WM)
  - `runtime.version` (runtime version stamp)

---

### Quick Start

1. **Apply required Termux:X11 patches:**

   ```bash
   tools/setup.sh
   ```

2. **Fetch prebuilt binaries:**
   Download and unpack prebuilt artifacts directly from the latest official release APK:

   ```bash
   tools/fetch_prebuilts.sh
   ```

   _(Optional: pass a specific release version, e.g. `tools/fetch_prebuilts.sh 0.6.3`)_

3. **Build and install directly to a connected device:**
   Use the `-PSKIP_CXX=true` flag to bypass C++ compilation and use the fetched prebuilts:
   ```bash
   ./gradlew installDebug -PSKIP_CXX=true
   ```

---

### Building from Source (C++ / NDK)

If you modify C++ native code or Termux:X11 sources and want to build everything from scratch, simply omit the flag:

```bash
./gradlew installDebug
```

---

### How to Update Prebuilts

When a new upstream release is published, update the prebuilts automatically:

```bash
# 1. Update prebuilts to target a new release version
tools/fetch_prebuilts.sh <NEW_VERSION>

# 2. Sync submodules and re-apply patches (if working with updated sources)
git submodule update --init --recursive
tools/setup.sh
```

## More

- [Playing](docs/playing.md): controls, controllers, settings and saves
- [Phones and drivers](docs/phones.md)
- [Building and how it works](docs/building.md)

Something wrong, or it works on your phone? [Open an issue](https://github.com/JulioFerrero/pocket-foundry/issues) with
what the Logs screen shows (its Copy button takes it all).

## Licence

GPL-3.0, as Termux:X11 is. Also bundled: glibc (LGPL-2.1), Mesa (MIT), SDL (zlib), liblzma (0BSD),
Titillium Web (OFL-1.1), Apache Commons Compress and AndroidX (Apache-2.0).

Factorio is © Wube Software. Pocket Foundry isn't affiliated with Wube and includes none of its files; the game
screenshots, the launcher's panels and buttons and the Home screen show art from the player's own copy.
