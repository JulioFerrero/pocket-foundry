# The on-screen controls' pictures

The controls, the toolbar and the FPS counter are drawn from sprites rendered here in Blender (Cycles), in the look of
Factorio's GUI made physical. Every model is original, made in code. The app reads them from
`app/src/main/assets/controls/`: lossy WebP and `sprites.json`, which says where each control's centre is and which
frames its press and release play.

Needs Blender 5.3, the website's Blender kit ([pocket-foundry-site](https://github.com/JulioFerrero/pocket-foundry-site)'s
`art/`, found beside this repository or at `PF_SITE_ART`), Pillow, ffmpeg and cwebp.

1. `blender -b -P art/controls/controls.py`: every sprite into `art/controls/sprites/`, one render at a time (about
   ten minutes on an M3 Pro). `--only NAME,NAME` renders some (`tool-hide`, `dpad-up`; `tools`, `toolbar` or `fps` for
   those sets), `--quick` a few frames at low quality to look at.
2. `python3 art/controls/place.py art/controls/sprites` and `python3 art/controls/place_tools.py art/controls/sprites`:
   previews, press videos, and the WebP files in `sprites/webp/`.
3. Copy `sprites/webp/*.webp` into `app/src/main/assets/controls/` and `sprites.json` there with each `file` ending in
   `.webp`.
