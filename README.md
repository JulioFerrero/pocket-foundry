# Pocket Foundry

**Play your own copy of Factorio on Android.** The official Linux ARM64 build from factorio.com runs natively on
the phone's GPU, played with an on-screen controller or a real one. No streaming, no Windows emulation, no game files in the app.

| | |
|---|---|
| ![Welcome: Pocket Foundry's blueprint](docs/screenshots/welcome.jpg) | ![Importing: the blueprint built in 3D](docs/screenshots/importing.jpg) |
| ![Home over a factory drawn from your own copy](docs/screenshots/home.jpg) | ![Playing with the on-screen controller](docs/screenshots/playing.jpg) |

## Playing

1. Buy Factorio and download the **Linux (arm64) .tar.xz** from [factorio.com/download](https://factorio.com/download).
2. Open Pocket Foundry, tap **Import Factorio** and pick the file.
3. **Continue** your newest save or open the **Main menu**. Back (or a controller's home button) opens the pause
   menu, and the game freezes while it's off screen.

Needs Android 10 or later on an Adreno GPU. Tested on a vivo X200 Ultra (Adreno 830, Android 16): 60 FPS and 60 UPS on an early-game save.

## Controls

Factorio runs in its gamepad mode, so its hints and the tutorial ("press A", "hold LB") name the buttons on screen.
Your finger is the cursor: touch the game where you want something to happen.

| On the game | What it does |
|---|---|
| Tap | Moves the cursor there and acts on it, like A: places what you hold, opens what's there. In menus, it clicks. |
| Double tap, keep the finger down and drag | Holds A along the way: a line of belts or pipes. |
| Long press | Mines what's under the finger (holds X); drag to keep mining. |
| Two-finger tap | B: closes a menu, goes back, dismisses a tutorial tip. |
| Pinch | Zooms in and out. |

| Button | In Factorio |
|---|---|
| Left stick | Walk. |
| Y (the largest) | Inventory and crafting. |
| A | Build, open, craft, pick up. |
| X | Mine, craft 5. |
| B | Close, back, empty the hand. |
| LB (hold) | Quick panel: items and tools without opening the inventory. |
| RT, LT | Held with another button for more: RT + A builds a ghost, LT + map opens research. |
| RB | Shoot. |
| D-pad | Right rotates, down picks up items, up shows details on the map. |
| The two by the toolbar | Map and game menu. |

The toolbar at the top moves, resizes (pinch a button) and fades the buttons, or hides them all.

**A controller**, USB or Bluetooth (Xbox, PlayStation, Switch Pro, 8BitDo, phone clamps), plays the game directly:
the on-screen buttons step aside as soon as you use it and come back when it's unplugged, and touch still moves the
cursor. Its home button opens Pocket Foundry's menu, and the game's rumble comes through the controller. A controller
connected when the game starts puts Factorio in its gamepad mode whatever Settings say. The launcher takes it too: the
D-pad or stick moves between buttons, lit as in the game, A picks and B goes back. The game names the buttons the
Xbox way; Factorio's own settings can show PlayStation or Switch symbols instead.

**Settings → Controls → Touch** is the other way to play: Factorio in its keyboard-and-mouse mode. Tap clicks, drag
holds the button (lines of belts, items across the inventory), long press mines, pinch zooms. A stick walks, buttons
carry the inventory, rotate, pipette, mining, map and menu keys, and the toolbar's keyboard opens Tab, Shift, Ctrl,
Alt, F, Z, T, Space and the phone's own keyboard.

## What's in it

- **Controls that match the game's hints** ([above](#controls)): your finger as the cursor, gestures for the common
  presses and Xbox buttons in the game's own look, sized by how much Factorio uses them.
- **Settings:** the resolution the game draws at, a 30 or 60 FPS cap (the game keeps 60 updates a second), a frame
  round the game that keeps the screen's rounded corners off its menus, sprite quality (Medium takes ~700 MB off a
  phone's shared memory, for very big saves), gamepad or touch controls, pausing in the background and vibration.
- **Saves:** export them all to `Download/Pocket Foundry`, where uninstalling leaves them, and import saves from anywhere.
- **Graphics driver:** Zink on Turnip (the default), freedreno, or a newer Turnip you import, such as the
  `turnip_*_arm64.tar.gz` builds from [lfdevs/mesa-for-android-container](https://github.com/lfdevs/mesa-for-android-container/releases).
  Pocket Foundry checks an imported driver can load before keeping it.

| | |
|---|---|
| ![Settings](docs/screenshots/settings.jpg) | ![Choosing the graphics driver](docs/screenshots/driver.jpg) |

## How it works

- **Your copy, imported:** the archive is unpacked into Pocket Foundry's private storage. The APK carries none of Factorio.
- **A small Linux runtime:** glibc built for Android's paths, Mesa's Zink on Turnip (OpenGL over Adreno's Vulkan),
  Termux:X11's X server as the screen and a tiny window manager. The game binary itself is never modified.
- **Sound and controls through SDL:** the game's static SDL3 hands over to Pocket Foundry's SDL 3.4 through SDL's dynamic
  API. That SDL plays audio through the app, adds a virtual gamepad driven by the on-screen controller or a real one
  (rumble goes back to the controller, or the phone) and paces frames for the 30 FPS cap. Touching the game reaches it through the X server, as a
  mouse (and, in Touch controls, a keyboard).
- **The launcher:** drawn with Factorio's own GUI sprites, read from your copy (`data/core/graphics/gui-new.png`, cut
  where the game's `style.lua` says): its panels, buttons, drop-downs and checkboxes, with the game's hover look on
  whatever a controller is on. Before the import, a look drawn
  in code. Behind it, a living backdrop: Pocket Foundry's own blueprint, built in 3D (three.js) while the game
  imports, then a working factory drawn from the sprites in your copy.

![The pause menu](docs/screenshots/paused.jpg)

## Building

```sh
tools/setup.sh                               # Termux:X11 with Pocket Foundry's patches
runtime/build.sh glibc mesa sdl wm assemble  # the Linux runtime, built in Docker
tools/build.sh                               # the debug APK
tools/build.sh :app:assembleRelease          # the release APK
```

Builds are signed with Pocket Foundry's key when `../pocketfoundry-keys/keystore.properties` exists (or `$POCKETFOUNDRY_SIGNING` points
at one), otherwise with the usual debug key. `tools/stage/` regenerates the launcher's scenes and `tools/icon_png.py`
the icon. `NOTES.md` has every finding and decision along the way.

## Licence

GPL-3.0, as Termux:X11 is. Also bundled: glibc (LGPL-2.1), Mesa (MIT), SDL (zlib), liblzma (0BSD), three.js (MIT),
Titillium Web (OFL-1.1), Apache Commons Compress and AndroidX (Apache-2.0).

Factorio is © Wube Software. Pocket Foundry isn't affiliated with Wube and includes none of its files; the game
screenshots, the launcher's panels and buttons and the Home screen show art from the player's own copy.
