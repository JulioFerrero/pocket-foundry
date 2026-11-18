"""
Puts the eighth pass's sprites where the app's default layout has its controls, and shows them pressing.

  python3 place.py [DIR]
Reads DIR/sprites.json (DIR is ./sprites unless given) and writes, next to this file:
  layout.png, layout-busy.png   the controls at rest over the dark ground and over a busy factory floor, 1920x872
  sheet.png                     every base and every strip of frames, named, over a grey checkerboard
  press.mp4                     the layout over the busy floor, each control pressed in turn: at real speed, then 4x slower
  press-closeup.mp4             the buttons, the stick, the D-pad, a bumper and a trigger, larger, the same way
and DIR/webp/: the sprites as lossy WebP, as the app would ship them; it prints what they weigh.
The two grounds, DIR/ground.png and ground-busy.png, are the fifth pass's, rendered by ../v5/controls.py.
Needs Pillow, ffmpeg and cwebp.
"""
import json
import os
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'sprites')
FONT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'res', 'font', 'titillium_web_semibold.ttf')
SCREEN = (3168, 1440)   # the phone; the sprites are drawn for it, one pixel for one
VIDEO = (1920, 872)
# a base (or None) and its caps, and the control's centre as a share of the screen: PadControl.kt's defaults
LAYOUT = [
    ('stick-base', ['stick-knob'], 0.10, 0.70), ('dpad-base', ['dpad-up'], 0.245, 0.88),
    ('wide-large-base', ['lb'], 0.075, 0.42), ('wide-small-base', ['lt'], 0.17, 0.42),
    ('wide-small-base', ['rb'], 0.83, 0.42), ('wide-large-base', ['rt'], 0.925, 0.42),
    ('small-base', ['l3'], 0.215, 0.56), ('small-base', ['r3'], 0.785, 0.56),
    ('small-base', ['view'], 0.4715, 0.18), ('small-base', ['menu'], 0.5285, 0.18),
    ('trackpad', [], 0.64, 0.74), ('face-base', ['a'], 0.885, 0.895), ('face-base', ['y'], 0.885, 0.635),
    ('face-base', ['x'], 0.827, 0.765), ('face-base', ['b'], 0.943, 0.765),
]
ORDER = ['a', 'b', 'x', 'y', 'dpad-up', 'dpad-right', 'dpad-down', 'dpad-left', 'dpad-up-right', 'lb', 'lt', 'rb', 'rt', 'l3', 'r3', 'view', 'menu', 'stick-knob']
HOLD, GAP = 8, 6        # frames a press is held for, and between one control and the next

info = json.load(open(f'{OUT}/sprites.json'))['sprites']
images = {n: Image.open(f"{OUT}/{c['file']}").convert('RGBA') for n, c in info.items()}


def frame(name, index):
    c = info[name]
    return images[name].crop((index * c['frameW'], 0, (index + 1) * c['frameW'], c['frameH']))


def draw(canvas, layout, state):
    """Draws the controls on [canvas]: [layout] is (base, caps, x, y) in pixels; [state] has, for a cap not at rest, the strip and frame to draw."""
    for base, caps, x, y in layout:
        c = info[base]
        canvas.alpha_composite(images[base], (round(x - c['cx']), round(y - c['cy'])))
    for base, caps, x, y in layout:
        for cap in caps:
            c = info[cap]
            shown, index = state.get(cap, (cap, 0))    # the D-pad's one cross is drawn from whichever way's strip is playing
            c = info[shown]
            canvas.alpha_composite(frame(shown, index), (round(x + c['offsetX'] - c['cx']), round(y + c['offsetY'] - c['cy'])))
    return canvas


def timeline(order, slow):
    """The frames of a film: each cap in [order] pressed, held and let go, each frame shown [slow] times."""
    shots = []
    for cap in order:
        c = info[cap]
        slot = 'dpad-up' if cap.startswith('dpad') else cap
        steps = [{slot: (cap, i)} for i in c['press'] + [c['held']] * HOLD + c['release']] + [{}] * GAP
        for step in steps:
            shots += [step] * slow
    return shots


def film(path, ground, layout, order, size, note_at):
    """Writes an H.264 film that loops: [order] at real speed, then 4x slower."""
    cache = {}
    font = ImageFont.truetype(FONT, 26)

    def picture(state, note):
        key = (tuple(state.values()), note)
        if key not in cache:
            canvas = draw(ground.copy(), layout, state).resize(size, Image.LANCZOS).convert('RGB')
            pen = ImageDraw.Draw(canvas)
            pen.text(note_at, note, font=font, fill='#FFE6C0', stroke_width=3, stroke_fill='#141314', anchor='mm')
            cache[key] = canvas.tobytes()
        return cache[key]
    encoder = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{size[0]}x{size[1]}',
                                '-r', '60', '-i', '-', '-c:v', 'libx264', '-preset', 'slow', '-crf', '15', '-pix_fmt', 'yuv420p',
                                '-movflags', '+faststart', path], stdin=subprocess.PIPE)
    for slow, note in ((1, 'real speed'), (4, '4x slower')):
        for state in timeline(order, slow):
            encoder.stdin.write(picture(state, note))
    encoder.stdin.close()
    encoder.wait()


place = [(base, caps, x * SCREEN[0], y * SCREEN[1]) for base, caps, x, y in LAYOUT]
for ground, picture in (('ground.png', 'layout.png'), ('ground-busy.png', 'layout-busy.png')):
    back = Image.open(f'{OUT}/{ground}').convert('RGBA').resize(SCREEN, Image.LANCZOS)
    draw(back.copy(), place, {}).resize(VIDEO, Image.LANCZOS).convert('RGB').save(f'{HERE}/{picture}')
film(f'{HERE}/press.mp4', back, place, ORDER, VIDEO, (VIDEO[0] // 2, VIDEO[1] - 40))

# The close-up: a part of the phone's screen 1.3 times over, a few controls set near each other.
ZOOM = 1.3
part = (round(VIDEO[0] / ZOOM), round(VIDEO[1] / ZOOM))
near = [('face-base', ['y'], 300, 150), ('face-base', ['x'], 120, 330), ('face-base', ['b'], 480, 330), ('face-base', ['a'], 300, 510),
        ('stick-base', ['stick-knob'], 840, 250), ('dpad-base', ['dpad-up'], 1270, 190),
        ('wide-large-base', ['lb'], 1280, 470), ('small-base', ['view'], 760, 560), ('wide-small-base', ['lt'], 980, 560)]
film(f'{HERE}/press-closeup.mp4', back.crop((500, 300, 500 + part[0], 300 + part[1])), near,
     ['a', 'b', 'dpad-up', 'dpad-right', 'dpad-down-left', 'lb', 'lt', 'view', 'stick-knob'], VIDEO, (VIDEO[0] // 2, VIDEO[1] - 40))

# The sheet: every base, then every strip, each under its name, over a checkerboard.
font = ImageFont.truetype(FONT, 26)
cells = [n for n in info if 'frames' not in info[n]] + [n for n in info if 'frames' in info[n]]
WIDE, PAD_, LABEL = 3300, 16, 36
spots, x, y, tall = [], PAD_, PAD_, 0
for n in cells:
    w, h = images[n].size
    if x + w + PAD_ > WIDE and x > PAD_:
        x, y, tall = PAD_, y + tall + LABEL + PAD_, 0
    spots.append((n, x, y))
    x, tall = x + w + PAD_, max(tall, h)
sheet = Image.new('RGBA', (WIDE, y + tall + LABEL + PAD_))
pen = ImageDraw.Draw(sheet)
for i in range(0, sheet.width, 24):
    for j in range(0, sheet.height, 24):
        pen.rectangle((i, j, i + 23, j + 23), fill='#8a8a8a' if (i // 24 + j // 24) % 2 else '#b4b4b4')
for n, x, y in spots:
    sheet.alpha_composite(images[n], (x, y))
    c = info[n]
    label = f"{n}  {c['frames']} x {c['frameW']}x{c['frameH']}" if 'frames' in c else f"{n}  {c['w']}x{c['h']}"
    pen.text((x, y + images[n].height + 2), label, font=font, fill='#101010')
sheet.convert('RGB').save(f'{HERE}/sheet.png')

# What the app would ship: lossy WebP, the alpha kept exact.
os.makedirs(f'{OUT}/webp', exist_ok=True)
png = webp = 0
for n, c in info.items():
    source, packed = f"{OUT}/{c['file']}", f"{OUT}/webp/{c['file'][:-4]}.webp"
    subprocess.run(['cwebp', '-quiet', '-q', '90', '-alpha_q', '100', '-m', '6', '-sharp_yuv', source, '-o', packed], check=True)
    png += os.path.getsize(source)
    webp += os.path.getsize(packed)
print(f'layout.png, layout-busy.png, sheet.png, press.mp4, press-closeup.mp4; {len(info)} sprites: PNG {png / 1024:.0f} KB, WebP {webp / 1024:.0f} KB')
