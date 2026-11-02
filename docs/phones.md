# Phones and drivers

Pocket Foundry draws the game with Mesa's Zink, which runs OpenGL on a Vulkan driver. It picks the driver for the
phone's GPU by itself, marked Recommended in **Settings -> Driver**:

- **Adreno 6xx and newer:** the bundled Turnip. It's the one Pocket Foundry tests.
- **Other GPUs with Vulkan 1.2:** the [Phone's driver](#the-phones-driver-any-gpu), experimental.
- **A driver that doesn't start:** the next game uses the next one, down to the [CPU](#any-other-phone-the-cpu).

Picking another driver there keeps it; picking the Recommended one hands the choice back. Community drivers can be
imported too (**Settings -> Driver -> Import**). Others make and test them; what's here comes from players' reports,
and a driver's bugs belong to its developers.

**Loads, but slow?** Check **Settings -> Driver**: CPU there means no GPU driver started on the phone. Its Last game
line says how each driver did.

## Adreno 6xx and newer (Snapdragon)

Works out of the box. Tested on a vivo X200 Ultra (Adreno 830, Android 16): 60 FPS and 60 UPS on an early-game save.

A newer Turnip can be imported, such as the `turnip_*_arm64.tar.gz` builds from
[lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container/releases). Freedreno
(OpenGL straight on the GPU) is in the same list. If text looks garbled, try Freedreno.

## The Phone's driver (any GPU)

Zink on the Vulkan driver the phone already has, loaded through [libhybris](https://github.com/taowen/libhybris)
with [MaliBridge](https://github.com/ASEbrahim/MaliBridge)'s fixes for Mali. Mali, PowerVR and Xclipse phones start
with it. It's experimental: it plays at 60 FPS on an Adreno 830, a Pixel 8's Mali-G715 and a Xiaomi 15T Pro's
Mali-G925. If it doesn't start, the next game runs on the CPU. How it runs on yours: [open an issue](https://github.com/JulioFerrero/pocket-foundry/issues).

## Xclipse (Samsung Exynos)

The [Phone's driver](#the-phones-driver-any-gpu) first. Or import `RADV-Xclipse-upstream-Linux.zip` from
[The412Banner/droiddeck-nonadreno-drivers](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/tag/radv-xclipse-upstream).
It's a community build of [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse), not made by Pocket
Foundry.

## Mali (MediaTek, Google Tensor, older Exynos)

Malis start on the [Phone's driver](#the-phones-driver-any-gpu). The community driver, PanVK, can be imported in
**Settings -> Driver -> Import**; which one depends on the Mali. Set **Sprite quality** to Medium.

| Mali | Driver | |
|---|---|---|
| G57, G68, G77, G78 | `libvulkan_panfrost-glibc-aarch64.so` from [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | Plays |
| G615 | `libvulkan_panfrost-glibc-aarch64.so` from [PanVK-kbase v26.3.1](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v26.3.1), with `DRIRC_CONFIGURATION=pan_enable_vertex_pipeline_stores_atomics=false` in **Settings -> Driver -> Options** (without it the game freezes once a save loads) | Plays |
| G610, G710 | The G615's, worth a try | The Phone's driver plays on a G710, about 40 FPS with High sprites ([#30](https://github.com/JulioFerrero/pocket-foundry/issues/30)); on a G610 it shows only black so far ([#27](https://github.com/JulioFerrero/pocket-foundry/issues/27)). With PanVK, the G615's stops halfway through the sprites on a G710, and the older [PanVK-Mali-G615-Linux.zip](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/download/panvk-mali-g615/PanVK-Mali-G615-Linux.zip) gets past the sprites on a G610, then stops |
| G715, G720, G925 | `libvulkan_panfrost-glibc-aarch64.so` from [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3), with `PANVK_DEBUG=noafbc,bc_compute` in **Settings -> Driver -> Options** | Reaches the main menu on a G720, slowly. The Phone's driver plays on a G715 ([#13](https://github.com/JulioFerrero/pocket-foundry/issues/13)) and a G925 ([#2](https://github.com/JulioFerrero/pocket-foundry/issues/2)) |
| G52, G76 | None to import yet: the drivers lack features the game needs | The Phone's driver plays on a G52, 35–52 FPS with Medium sprites and Light graphics ([#1](https://github.com/JulioFerrero/pocket-foundry/issues/1)) |

These are community builds from
[zenithblue-oss/panvk-kbase-android](https://github.com/zenithblue-oss/panvk-kbase-android) and
[The412Banner/droiddeck-nonadreno-drivers](https://github.com/The412Banner/droiddeck-nonadreno-drivers), not made by
Pocket Foundry. Each release has a build for Android and one for Linux: Pocket Foundry needs the glibc (Linux) one.
Status and reports: [Mali GPUs](https://github.com/JulioFerrero/pocket-foundry/issues/21).

## PowerVR and Adreno 5xx

PowerVR (Pixel 10) starts on the [Phone's driver](#the-phones-driver-any-gpu), untested there so far. Adreno 5xx and
older can't run Turnip, and their kernels lack what freedreno needs: they use the [CPU](#any-other-phone-the-cpu).

## Any other phone: the CPU

**CPU** in **Settings -> Driver** draws the game on the phone's processor with Mesa's llvmpipe, no GPU driver
needed. Phones where no GPU driver starts end up on it. It's experimental: slow, and it uses a lot of battery. How fast it
runs depends on the processor, not on Pocket Foundry.

Choosing it also sets Medium sprites, **Graphics: Light** (Factorio's smoke, clouds, decoratives, shadows and terrain
effects off) and the lowest resolution, which keeps the same view, only softer. A vivo X200 Ultra runs it at 60 FPS;
phones with slower processors will run lower. How it runs on yours:
[open an issue](https://github.com/JulioFerrero/pocket-foundry/issues).

## Reported working

From players' reports; Pocket Foundry itself only tests the vivo.

| Phone | GPU | Driver | |
|---|---|---|---|
| vivo X200 Ultra | Adreno 830 | Bundled | 60 FPS |
| OnePlus 15 | Adreno 840 | Bundled | |
| Galaxy S24 Ultra | Adreno 750 | Bundled | |
| Xiaomi 13 | Adreno 740 | Bundled | |
| POCO F5 Pro | Adreno 730 | Bundled | 60 FPS ([#20](https://github.com/JulioFerrero/pocket-foundry/issues/20)) |
| Galaxy Z Fold 8 Ultra | Adreno | Bundled | Folded and unfolded |
| Retroid Pocket Flip 2 | Adreno 650 | Bundled | With its own controls |
| Galaxy S20 FE | Adreno 650 | Bundled | Since 0.6.1 ([#12](https://github.com/JulioFerrero/pocket-foundry/issues/12)) |
| Redmi Note 10 Pro | Adreno 618 | Bundled | Since 0.6.2 ([#17](https://github.com/JulioFerrero/pocket-foundry/issues/17)) |
| Nothing Phone (1) | Adreno 642L | Freedreno | Turnip garbles the main menu's text |
| Galaxy S25 FE | Xclipse 940 | [RADV-Xclipse-upstream](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/tag/radv-xclipse-upstream) | 60 FPS ([#18](https://github.com/JulioFerrero/pocket-foundry/issues/18)) |
| Pixel 8 | Mali-G715 | Phone's driver | 60 FPS, Medium sprites ([#13](https://github.com/JulioFerrero/pocket-foundry/issues/13)) |
| Xiaomi 15T Pro | Mali-G925 | Phone's driver | 60 FPS, Medium sprites ([#2](https://github.com/JulioFerrero/pocket-foundry/issues/2)) |
| OnePlus Nord 3 5G | Mali-G710 | Phone's driver | About 40 FPS, High sprites, full graphics ([#30](https://github.com/JulioFerrero/pocket-foundry/issues/30)) |
| Infinix Hot 60i | Mali-G52 | Phone's driver | 35–52 FPS, Medium sprites, Light graphics ([#1](https://github.com/JulioFerrero/pocket-foundry/issues/1)) |
| Pixel 6 | Mali-G78 | [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | Medium sprites ([#11](https://github.com/JulioFerrero/pocket-foundry/issues/11)) |
| Xiaomi 11T | Mali-G77 | [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | 60 FPS, Medium sprites ([#45](https://github.com/JulioFerrero/pocket-foundry/issues/45)) |
| Poco M6 Pro 4G | Mali-G57 | [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | 25–30 FPS, 60 UPS ([#25](https://github.com/JulioFerrero/pocket-foundry/issues/25)) |
| Poco X6 Pro | Mali-G615 | [PanVK-kbase v26.3.1](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v26.3.1) with the G615's Options | Plays ([#10](https://github.com/JulioFerrero/pocket-foundry/issues/10)) |
| realme 16 Pro | Mali-G615 | [PanVK-kbase v26.3.1](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v26.3.1) with the G615's Options | 60 FPS, High sprites ([#35](https://github.com/JulioFerrero/pocket-foundry/issues/35)) |

Yours works, or doesn't? [Open an issue](https://github.com/JulioFerrero/pocket-foundry/issues) with the Logs
screen's Copy.

## Importing a driver

- Linux (glibc) builds only. Android builds (AdrenoTools, Winlator, emulator packages) can't load in the game.
- A `.tar.gz`, `.tar.xz`, `.zip` or the `.so` alone. Libraries in the package come along.
- Pocket Foundry checks that the driver loads before keeping it.
- Each driver's **Options** pass `NAME=value` settings to it (`PANVK_DEBUG=…`, `TU_DEBUG=…`). Logs shows them.

![Choosing the graphics driver](screenshots/driver.jpg)
