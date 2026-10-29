# Phones and drivers

Pocket Foundry draws the game with Mesa's Zink, which runs OpenGL on a Vulkan driver. The bundled driver, Turnip,
only drives Adreno GPUs. For other GPUs you can import a community driver in **Settings → Driver → Import**.

## Adreno 6xx and newer (Snapdragon)

Works out of the box. Tested on a vivo X200 Ultra (Adreno 830, Android 16): 60 FPS and 60 UPS on an early-game save.

A newer Turnip can be imported, such as the `turnip_*_arm64.tar.gz` builds from
[lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container/releases). freedreno
(OpenGL straight on the GPU) is in the same list.

## Xclipse (Samsung Exynos)

Import `RADV-Xclipse-upstream-Linux.zip` from
[The412Banner/droiddeck-nonadreno-drivers](https://github.com/The412Banner/droiddeck-nonadreno-drivers/releases/tag/radv-xclipse-upstream).
It's a community build of [JimVulkan/radv-xclipse](https://github.com/JimVulkan/radv-xclipse), not made by Pocket
Foundry.

## Mali (MediaTek, Google Tensor, older Exynos)

Import `libvulkan_panfrost-glibc-aarch64.so` from
[PanVK-kbase RC3](https://github.com/zenithblue-oss/panvk-kbase-android/releases/tag/panvk-kbase-v0.1.0-rc3), a
community driver for Mali v9 to v13 (G57, G68, G77, G78 and newer). Set **Sprite quality** to Medium.

- Mali-G615 crashes while the game loads its sprites ([#10](https://github.com/JulioFerrero/pocket-foundry/issues/10)).
- Older Malis (G52, G76) aren't covered yet.

## PowerVR and Adreno 5xx

PowerVR (Pixel 10) has no driver that works here yet. Adreno 5xx and older can't run Turnip, and their kernels lack
what freedreno needs.

## Known to work

| Phone | GPU | Driver | |
|---|---|---|---|
| vivo X200 Ultra | Adreno 830 | Bundled | 60 FPS |
| Galaxy S25 FE | Xclipse 940 | RADV-Xclipse-upstream | 60 FPS ([#18](https://github.com/JulioFerrero/pocket-foundry/issues/18)) |
| Pixel 6 | Mali-G78 | PanVK-kbase RC3 | Medium sprites ([#11](https://github.com/JulioFerrero/pocket-foundry/issues/11)) |

Yours works, or doesn't? [Open an issue](https://github.com/JulioFerrero/pocket-foundry/issues) with the Logs
screen's Copy.

## Importing a driver

- Linux (glibc) builds only. Android builds (AdrenoTools, Winlator, emulator packages) can't load in the game.
- A `.tar.gz`, `.tar.xz`, `.zip` or the `.so` alone. Libraries in the package come along.
- Pocket Foundry checks that the driver loads before keeping it.
- **Options** passes `NAME=value` settings to the driver (`PANVK_DEBUG=…`, `TU_DEBUG=…`). Logs shows them.

![Choosing the graphics driver](screenshots/driver.jpg)
