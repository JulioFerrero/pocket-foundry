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
tools/setup.sh                                       # Termux:X11 with Pocket Foundry's patches
runtime/build.sh glibc mesa sdl wm hybris assemble   # the Linux runtime, built in Docker
tools/build.sh                                       # the debug APK
tools/build.sh :app:assembleRelease                  # the release APK
```

Builds are signed with Pocket Foundry's key when `../pocketfoundry-keys/keystore.properties` exists (or
`$POCKETFOUNDRY_SIGNING` points at one; relative paths start at the repo root), otherwise with the usual debug
key. `tools/icon_png.py` regenerates the icon.

## Releasing

Changes go to `dev` first. There, `tools/release.sh 0.8.17-beta.1` bumps the version and pushes a tag; GitHub
builds it from source ([release.yml](../.github/workflows/release.yml): the runtime on arm64, the APK, the tests) and
publishes a pre-release, which the app's update check skips. Once the betas work, merge `dev` into `main` and run
`tools/release.sh 0.8.17` there for the release.

Every APK carries a build attestation: `gh attestation verify pocket-foundry-0.8.17.apk -R JulioFerrero/pocket-foundry`
shows the commit and the run that built it. The build signs with Pocket Foundry's key from the repository's Actions
secrets: `POCKETFOUNDRY_KEYSTORE` (the keystore, base64) and `POCKETFOUNDRY_STORE_PASSWORD`, `_KEY_ALIAS`,
`_KEY_PASSWORD`.

## Helping

Open fronts, beyond testing on your phone:

- **Mali, PowerVR and Xclipse GPUs.** The Phone's driver (Zink on the phone's own Vulkan driver, through libhybris)
  is how all three start now. It needs testing on them.
- **Other Adreno generations:** which Turnip works on each.
- **Other screens:** foldables, 4:3, tablets, and the on-screen controls on them.
- **Other Android skins:** how each treats a game running in the background.
