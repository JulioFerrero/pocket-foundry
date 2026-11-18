"""
Shows the toolbar and the FPS counter of the eighth pass, and packs their sprites.

  python3 place_tools.py [DIR]
Reads DIR/sprites.json (DIR is ./sprites unless given) and writes, next to this file:
  toolbar-preview.png   the three-key row and the editor's seven-key row over the busy ground, one key of each pressed
  fps-preview.png       the counter with a made-up number and graph drawn in as the app would, under its glass
  toolbar-press.mp4     the keys pressed in turn: at real speed, then 4x slower
and DIR/webp/: the toolbar's and the counter's sprites as lossy WebP; it prints what all of DIR/webp weighs.
Needs Pillow, ffmpeg and cwebp.
"""
import glob
import json
import math
import os
import subprocess
import sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, 'sprites')
FONTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'res', 'font', '')
SCREEN = (3168, 1440)
sheet = json.load(open(f'{OUT}/sprites.json'))
info, bar = sheet['sprites'], sheet['toolbar']
mine = [n for n in info if n.startswith(('tool', 'fps'))]
images = {n: Image.open(f"{OUT}/{info[n]['file']}").convert('RGBA') for n in mine}
PLAY = ['adjust', 'keyboard', 'hide']
EDIT = ['add', 'remove', 'smaller', 'bigger', 'opacity', 'reset', 'done']


def frame(name, index):
    c = info[name]
    return images[name].crop((index * c['frameW'], 0, (index + 1) * c['frameW'], c['frameH']))


def row(canvas, tools, centre_x, top, state):
    """Draws a toolbar: its panel joined from the three pieces, [top] its panel's top, then each key's cap."""
    left, middle, right = (images[bar[k]] for k in ('left', 'middle', 'right'))
    x = round(centre_x - len(tools) * bar['keyPitch'] / 2 - bar['panelEnd'] - bar['panelLeft'])
    y = round(top - bar['panelTop'])
    at = x
    for piece in [left] + [middle] * (len(tools) - 1) + [right]:
        canvas.alpha_composite(piece, (at, y))
        at += piece.width
    for i, tool in enumerate(tools):
        c = info[f'tool-{tool}']
        canvas.alpha_composite(frame(f'tool-{tool}', state.get(tool, 0)),
                               (round(x + bar['firstKeyX'] + i * bar['keyPitch'] - c['cx']), round(y + bar['keyY'] - c['cy'])))


ground = Image.open(f'{OUT}/ground-busy.png').convert('RGBA').resize(SCREEN, Image.LANCZOS)
held = info['tool-add']['held']

# The preview: the top of the phone's screen, the two rows one under the other.
canvas = ground.crop((784, 0, 784 + 1600, 400))
row(canvas, PLAY, 800, 36, {'keyboard': held})
row(canvas, EDIT, 800, 220, {'add': held})
canvas.convert('RGB').save(f'{HERE}/toolbar-preview.png')

# The film: the same view, every key pressed in turn.
size = (1600, 400)
shots = []
for slow in (1, 4):
    for tool in PLAY + EDIT:
        c = info[f'tool-{tool}']
        for i in c['press'] + [c['held']] * 8 + c['release'] + [0] * 6:
            shots += [(tool, i, slow)] * slow
cache = {}
font = ImageFont.truetype(FONTS + 'titillium_web_semibold.ttf', 24)
encoder = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{size[0]}x{size[1]}', '-r', '60',
                            '-i', '-', '-c:v', 'libx264', '-preset', 'slow', '-crf', '15', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                            f'{HERE}/toolbar-press.mp4'], stdin=subprocess.PIPE)
for tool, i, slow in shots:
    if (tool, i, slow) not in cache:
        canvas = ground.crop((784, 0, 784 + size[0], size[1]))
        row(canvas, PLAY, 800, 36, {tool: i})
        row(canvas, EDIT, 800, 220, {tool: i})
        ImageDraw.Draw(canvas).text((800, 376), 'real speed' if slow == 1 else '4x slower', font=font, fill='#FFE6C0', stroke_width=3,
                                    stroke_fill='#141314', anchor='mm')
        cache[(tool, i, slow)] = canvas.convert('RGB').tobytes()
    encoder.stdin.write(cache[(tool, i, slow)])
encoder.stdin.close()
encoder.wait()

# The counter: what the app draws, drawn here the same way, between the panel and its glass.
c = info['fps-panel']
panel = images['fps-panel'].copy()
pen = ImageDraw.Draw(panel)
t, g = c['text'], c['graph']
pen.text((t['numberRight'], t['numberBaseline']), '60', font=ImageFont.truetype(FONTS + 'titillium_web_bold.ttf', t['numberSize']), fill='#FFFFFF', anchor='rs')
pen.text((t['numberRight'], t['unitBaseline']), 'FPS', font=ImageFont.truetype(FONTS + 'titillium_web_semibold.ttf', t['unitSize']), fill='#C9C0B4', anchor='rs')
pen.line((g['x'], g['y'] + g['h'], g['x'] + g['w'], g['y'] + g['h']), fill='#41404099', width=3)
points = []
for i in range(80):
    dip = 0.5 * math.exp(-((i - 52) / 5) ** 2) + 0.18 * math.exp(-((i - 20) / 3) ** 2)
    points.append((g['x'] + g['w'] * i / 79, g['y'] + g['h'] * (0.14 + dip + 0.03 * math.sin(i * 1.7))))
pen.line(points, fill='#5EB663', width=5, joint='curve')
both = Image.new('RGBA', (panel.width * 2 + 120, panel.height + 80))
tile = ground.crop((300, 500, 300 + both.width, 500 + both.height))
bare = panel.copy()
panel.alpha_composite(images['fps-glass'])
tile.alpha_composite(bare, (40, 40))               # without the glass, and with it
tile.alpha_composite(panel, (80 + panel.width, 40))
small = panel.resize((panel.width // 2, panel.height // 2), Image.LANCZOS)   # and as large as on the phone
out = Image.new('RGBA', (tile.width, tile.height + small.height + 40))
out.paste(tile, (0, 0))
under = ground.crop((300, 500 + tile.height, 300 + tile.width, 500 + out.height))
under.alpha_composite(small, (40, 20))
out.paste(under, (0, tile.height))
out.convert('RGB').save(f'{HERE}/fps-preview.png')

os.makedirs(f'{OUT}/webp', exist_ok=True)
for n in mine:
    subprocess.run(['cwebp', '-quiet', '-q', '90', '-alpha_q', '100', '-m', '6', '-sharp_yuv', f"{OUT}/{info[n]['file']}", '-o',
                    f"{OUT}/webp/{info[n]['file'][:-4]}.webp"], check=True)
new = sum(os.path.getsize(f"{OUT}/webp/{info[n]['file'][:-4]}.webp") for n in mine)
total = sum(os.path.getsize(f) for f in glob.glob(f'{OUT}/webp/*.webp'))
print(f'toolbar-preview.png, fps-preview.png, toolbar-press.mp4; {len(mine)} sprites: WebP {new / 1024:.0f} KB; all of webp/: {total / 1024:.0f} KB')
