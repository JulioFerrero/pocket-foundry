# Building and how it works

## How it works

- **Your copy, imported:** the archive is unpacked into Pocket Foundry's private storage. The APK carries none of Factorio.
- **A small Linux runtime:** glibc built for Android's paths, Mesa's Zink on Turnip (OpenGL over Adreno's Vulkan),
  Termux:X11's X server as the screen and a tiny window manager. The game binary itself is never modified.
- **Sound and controls through SDL:** the game's static SDL3 hands over to Pocket Foundry's SDL 3.4 through SDL's
  dynamic API. That SDL plays audio through the app, adds a virtual gamepad driven by the on-screen controller or a
  real one, and paces frames for the 30 FPS cap. It also tells the game how many pixels make a display point, so the
  UI scale you pick in Factorio looks the same at every resolution. Touch reaches the game through the X server, as
  a mouse.
- **The launcher:** drawn with Factorio's own GUI sprites, read from your copy (`data/core/graphics/gui-new.png`, cut
  where the game's `style.lua` says). Behind it, Pocket Foundry's own blueprint, built piece by piece while the game
  imports, then a working factory drawn from the sprites in your copy.

## Building

```sh
tools/setup.sh                               # Termux:X11 with Pocket Foundry's patches
runtime/build.sh glibc mesa sdl wm assemble  # the Linux runtime, built in Docker
tools/build.sh                               # the debug APK
tools/build.sh :app:assembleRelease          # the release APK
```

Builds are signed with Pocket Foundry's key when `../pocketfoundry-keys/keystore.properties` exists (or
`$POCKETFOUNDRY_SIGNING` points at one), otherwise with the usual debug key. `tools/stage/` regenerates the
launcher's scenes and `tools/icon_png.py` the icon.

## Helping

Open fronts, beyond testing on your phone:

- **Mali, PowerVR and Xclipse GPUs.** The likeliest path for all three is a wrapper over the phone's own Vulkan
  driver, which DroidDeck is building for the same kind of Linux-on-Android setup.
- **Other Adreno generations:** which Turnip works on each.
- **Other screens:** foldables, 4:3, tablets, and the on-screen controls on them.
- **Other Android skins:** how each treats a game running in the background.
