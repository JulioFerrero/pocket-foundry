# Phase 1: Factorio in Termux

Prototype that runs the official linux-arm64 build through Termux, a Debian 13
proot container and Termux:X11. It exists to measure; the launcher APK replaces it.

## On the computer (adb)

1. Install Termux from its GitHub releases (`termux-app_v0.118.3+github-debug_arm64-v8a.apk`)
   and the **sharedUid** nightly of Termux:X11 (`termux-x11-universal-sharedUid-debug.apk`).
   Both are signed with the same key. Sharing Termux's user id keeps vivo's power
   manager from freezing the X server and the game while Termux:X11 is in front.
2. Stop Android from killing Termux's child processes:
   ```sh
   adb shell settings put global settings_enable_monitor_phantom_procs false
   adb shell device_config set_sync_disabled_for_tests persistent
   adb shell device_config put activity_manager max_phantom_processes 2147483647
   ```
3. Copy `termux/*.sh` to `~/pocketfoundry/` in Termux and your own
   `factorio_linux-arm64_*.tar.xz` (factorio.com, Linux arm64) to `~`.
   An easy way is `sshd` in Termux plus `adb forward tcp:8022 tcp:8022` and `scp -P 8022`.

## In Termux

```sh
~/pocketfoundry/setup.sh ~/factorio_linux-arm64_2.1.23.tar.xz   # once
~/pocketfoundry/run-factorio.sh                                   # every time
~/pocketfoundry/run-factorio.sh --load-game /opt/factorio/saves/NAME.zip
GL=kgsl ~/pocketfoundry/run-factorio.sh                           # freedreno instead of Zink
```

Saves and `config/config.ini` live in `/opt/factorio` inside the container.

## Input

- Termux:X11 is set to direct touch: a tap is a left click at that point.
  The back button toggles the soft keyboard.
- With a computer: `scrcpy --keyboard=uhid --mouse=uhid` makes its keyboard and
  mouse appear to the phone as real USB devices.
- FPS/UPS counter: F4 → `show-fps`.
