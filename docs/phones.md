# Phones and drivers

Pocket Foundry draws the game with Mesa's Zink, which runs OpenGL on a Vulkan driver. The bundled driver, Turnip,
only drives Adreno GPUs. For other GPUs you can import a community driver in **Settings -> Driver -> Import**.

## Adreno 6xx and newer (Snapdragon)

Works out of the box. Tested on a vivo X200 Ultra (Adreno 830, Android 16): 60 FPS and 60 UPS on an early-game save.

A newer Turnip can be imported, such as the `turnip_*_arm64.tar.gz` builds from
[lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container/releases). Freedreno
(OpenGL straight on the GPU) is in the same list. If text looks garbled, try Freedreno.

## Xclipse (Samsung Exynos)

Import `RADV-Xclipse-upstream-Linux.zip` from
[The412Banner/droiddeck-nonadreno-drivers](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/tag/radv-xclipse-upstream).
It's a community build of [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse), not made by Pocket
Foundry.

## Mali (MediaTek, Google Tensor, older Exynos)

Malis need a community driver, PanVK, imported in **Settings -> Driver -> Import**. Which one depends on the Mali.
Set **Sprite quality** to Medium.

| Mali | Driver | |
|---|---|---|
| G57, G68, G77, G78 | `libvulkan_panfrost-glibc-aarch64.so` from [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | Plays |
| G615 | [PanVK-Mali-G615-Linux.zip](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/download/panvk-mali-g615/PanVK-Mali-G615-Linux.zip) | Plays |
| G610, G710 | [PanVK-Mali-G615-Linux.zip](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/download/panvk-mali-g615/PanVK-Mali-G615-Linux.zip) | Gets past the sprites on a G610, then stops; being worked on |
| G715, G720, G925 | `libvulkan_panfrost-glibc-aarch64.so` from [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3), with `PANVK_DEBUG=noafbc,bc_compute` in **Settings -> Driver -> Options** | Reaches the main menu on a G720, slowly |
| G52, G76 | None yet: the drivers lack features the game needs | |

The `.zip` drivers are community builds from
[The412Banner/droiddeck-nonadreno-drivers](https://github.com/The412Banner/droiddeck-nonadreno-drivers), not made
by Pocket Foundry. Status and reports: [Mali GPUs](https://github.com/JulioFerrero/pocket-foundry/issues/21).

## PowerVR and Adreno 5xx

PowerVR (Pixel 10) has no driver that works here yet. Adreno 5xx and older can't run Turnip, and their kernels lack
what freedreno needs.

## Known to work

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
| Pixel 6 | Mali-G78 | [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | Medium sprites ([#11](https://github.com/JulioFerrero/pocket-foundry/issues/11)) |
| Poco M6 Pro 4G | Mali-G57 | [PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3) | 25–30 FPS, 60 UPS ([#25](https://github.com/JulioFerrero/pocket-foundry/issues/25)) |
| Poco X6 Pro | Mali-G615 | [PanVK-Mali-G615](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/download/panvk-mali-g615/PanVK-Mali-G615-Linux.zip) | Past the loading screen ([#10](https://github.com/JulioFerrero/pocket-foundry/issues/10)) |

Yours works, or doesn't? [Open an issue](https://github.com/JulioFerrero/pocket-foundry/issues) with the Logs
screen's Copy.

## Importing a driver

- Linux (glibc) builds only. Android builds (AdrenoTools, Winlator, emulator packages) can't load in the game.
- A `.tar.gz`, `.tar.xz`, `.zip` or the `.so` alone. Libraries in the package come along.
- Pocket Foundry checks that the driver loads before keeping it.
- **Options** passes `NAME=value` settings to the driver (`PANVK_DEBUG=…`, `TU_DEBUG=…`). Logs shows them.

![Choosing the graphics driver](screenshots/driver.jpg)
