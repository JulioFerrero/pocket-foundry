# Pocket Foundry

[![Release](https://img.shields.io/github/v/release/JulioFerrero/pocket-foundry)](https://github.com/JulioFerrero/pocket-foundry/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/JulioFerrero/pocket-foundry/total)](https://github.com/JulioFerrero/pocket-foundry/releases)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#getting-started)
[![License](https://img.shields.io/github/license/JulioFerrero/pocket-foundry)](LICENSE)
[![StandWithUkraine](https://raw.githubusercontent.com/vshymanskyy/StandWithUkraine/main/badges/StandWithUkraine.svg)](https://github.com/vshymanskyy/StandWithUkraine/blob/main/docs/README.md)

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

Releases are signed with the certificate whose SHA-256 is
`0e301617d64bc294bcc2d1dbee71a8c83f8fcc7b7c3616edc26e9b2e89c7e35f`.

## Phones

Pocket Foundry picks the driver for the phone's GPU by itself. If it doesn't start, the next game uses the next one.

| GPU                  | Found in                              | Runs with                                                    |
| -------------------- | ------------------------------------- | ------------------------------------------------------------ |
| Adreno 6xx and newer | Snapdragon                            | Turnip, bundled and tested                                   |
| Mali                 | MediaTek, Google Tensor, older Exynos | The phone's own driver (experimental), or a community driver |
| Xclipse              | Exynos (Galaxy S24, S25)              | The phone's own driver (experimental), or a community driver |
| PowerVR              | Pixel 10                              | The phone's own driver (experimental)                        |
| Adreno 5xx and older | Older Snapdragon                      | The CPU                                                      |

The phone's own driver is new and only tested on an Adreno so far: reports from Mali, Xclipse and PowerVR phones help.

**Loads, but slow?** It may be on the CPU, where the game ends up when no GPU driver starts. **Settings -> Driver**
shows which one is in use and how the last game with each went. [Phones and drivers](docs/phones.md) has the
community drivers and the phones people report working.

## More

- [Playing](docs/playing.md): controls, controllers, settings, saves and game files
- [Phones and drivers](docs/phones.md)
- [Building and how it works](docs/building.md)

Something wrong, or it works on your phone? [Open an issue](https://github.com/JulioFerrero/pocket-foundry/issues) with
what the Logs screen shows (its Copy button takes it all).

## Licence

GPL-3.0, as Termux:X11 is. Also bundled: glibc (LGPL-2.1), Mesa (MIT), SDL (zlib), liblzma (0BSD),
Titillium Web (OFL-1.1), Apache Commons Compress and AndroidX (Apache-2.0). The Phone's driver loads through
[libhybris](https://github.com/taowen/libhybris) (Apache-2.0), whose Vulkan libraries (GPL-3.0) include
[MaliBridge by ASEbrahim](https://github.com/ASEbrahim/MaliBridge).

Factorio is © Wube Software. Pocket Foundry isn't affiliated with Wube and includes none of its files; the game
screenshots, the launcher's panels and buttons and the Home screen show art from the player's own copy.
