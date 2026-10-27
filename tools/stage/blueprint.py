"""Pocket Foundry's own blueprint factory (no Factorio assets): the Welcome screen's SVG, and the plan the
Importing screen's 3D scene (assets/stage/build.js) builds as the import goes.

Plates come in from the left; inserters lift them into assemblers, output inserters put gears on
the top belt. Science comes in from the right; inserters feed it into labs. The belts are simulated:
an inserter takes one real item off its belt, so the belt thins out downstream.

mode "welcome": buildings are placed as ghosts and built one after another.
mode "import":  each building is built when --p (the import percent) passes its threshold. Until an
                inserter is built, the items it would take flow past it.

python3 tools/stage/blueprint.py app/src/main/assets/stage"""
import json
import math
import sys

LINE = "#CFE3FF"
GHOST = "rgba(140,190,255,0.55)"
AMBER = "#F1A240"
CYAN = "#7DCAED"

TICK = 0.05                 # s
CYCLE = 64                  # ticks per inserter swing: 3.2 s
PERIOD = 640                # ticks: one trip of an item slot along its belt, 32 s
SPEED = 1.5                 # px per tick: 30 px/s, a yellow belt at 16 px a tile
GAP = 12                    # px between items
X0, X1 = -40, 920           # belts run off both edges; X1 - X0 = SPEED * PERIOD
LENGTH = X1 - X0
SLOTS = LENGTH // GAP
GRAB, DROP = 0, 30          # ticks into a swing: the hand closes on an item, the hand lets go
WAIT = 7                    # ticks the arm rests at either end, so it can wait a little for an item or a gap
assert SPEED * PERIOD == LENGTH and PERIOD % CYCLE == 0 and LENGTH % GAP == 0


def gear(cx, cy, outer, inner, teeth=8):
    pts = []
    for k in range(teeth * 4):
        a = 2 * math.pi * k / (teeth * 4)
        r = outer if k % 4 in (1, 2) else inner
        pts.append(f"{cx + r * math.cos(a):.1f} {cy + r * math.sin(a):.1f}")
    return "M " + " L ".join(pts) + " Z"


def octagon(x, y, s, cut):
    p = [(x + cut, y), (x + s - cut, y), (x + s, y + cut), (x + s, y + s - cut), (x + s - cut, y + s), (x + cut, y + s), (x, y + s - cut), (x, y + cut)]
    return "M " + " L ".join(f"{a} {b}" for a, b in p) + " Z"


ITEMS = {
    "plate": '<rect x="-4" y="-3" width="8" height="6" fill="#DDE9F7"/>',
    "gear": f'<path d="{gear(0, 0, 4.4, 3.2, 6)}" fill="#B9C9DC"/><circle r="1.3" fill="#0E2238"/>',
    "science": f'<path d="M -2.5 -4 H 2.5 L 4 4 H -4 Z" fill="{AMBER}"/>',
}


class Belt:
    def __init__(self, y, direction, full, item):
        self.y, self.direction, self.full, self.item = y, direction, full, item
        self.present = [full] * SLOTS
        self.actor = [None] * SLOTS            # the inserter that empties or fills the slot on its trip
        self.recording = False                 # only the settled loop counts, not the warm-up
        self.history = [[] for _ in range(SLOTS)]

    def u(self, k, n):
        return (k * GAP + SPEED * n) % LENGTH

    def x(self, k, n):
        return X0 + self.u(k, n) if self.direction > 0 else X1 - self.u(k, n)

    def step(self, n):
        for k in range(SLOTS):
            if n > 0 and self.u(k, n) < self.u(k, n - 1):     # off the far end, back in at the start
                self.present[k] = self.full

    def flip(self, at, n, want, who):
        """Takes the item (want=True) or fills the gap (want=False) under the hand, if there is one."""
        near = [k for k in range(SLOTS) if abs(self.x(k, n) - at) <= 4 and self.present[k] == want]
        if not near:
            return False
        k = min(near, key=lambda k: abs(self.x(k, n) - at))
        self.present[k] = not want
        if self.recording:
            assert self.actor[k] in (None, who), "a slot changing hands"
            self.actor[k] = who
        return True


class Inserter:
    def __init__(self, n, cx, cy, pick, belt, takes, item, phase):
        self.n, self.cx, self.cy, self.pick, self.belt, self.takes, self.item, self.phase = n, cx, cy, pick, belt, takes, item, phase
        self.holding = False
        self.history = []

    def tick(self, n, belt):
        c = (n - self.phase) % CYCLE
        if self.takes:                                   # belt -> machine
            if not self.holding and GRAB <= c <= GRAB + WAIT:
                self.holding = belt.flip(self.cx, n, True, self.n)
            elif self.holding and c == DROP:
                self.holding = False
        else:                                            # machine -> belt
            if c == GRAB:
                self.holding = True
            elif self.holding and DROP <= c <= DROP + WAIT:
                self.holding = not belt.flip(self.cx, n, False, self.n)


def timing(x, direction, residue, window):
    """A swing timing that puts the hand at x while a slot of the given residue (mod the slots a
    swing lets by) passes: inserters on one belt with different residues never compete for an item."""
    per_swing = int(SPEED * CYCLE // GAP)
    u = x - X0 if direction > 0 else X1 - x
    for phase in range(CYCLE):
        for n in range(phase + window, phase + window + WAIT + 1):
            k = (u - SPEED * n) / GAP
            if abs(k - round(k)) * GAP <= 4:
                if round(k) % per_swing == residue:
                    return phase
                break
    raise AssertionError("no timing")


RESIDUES = [0, 3, 6, 1, 4, 7]


def build(mode):
    welcome = mode == "welcome"
    belts = {"gear": Belt(12, 1, False, "gear"), "plate": Belt(108, 1, True, "plate"), "science": Belt(292, -1, True, "science")}
    asm_x = [56 + 136 * i for i in range(6)]
    lab_x = [56 + 136 * i for i in range(6)]
    inserters, when = [], {}

    def at(order_welcome, order_import):
        return order_welcome if welcome else order_import

    for i, x in enumerate(asm_x):
        t = at(0.7 + i * 0.32, 6 + i * 7)
        when[("asm", i)] = t
        for kind, cy, belt, takes, item, extra in (("in", 92, "plate", True, "plate", at(0.1, 1)), ("out", 28, "gear", False, "gear", at(0.15, 2))):
            ins = Inserter(len(inserters), x + 24, cy, 1, belt, takes, item, timing(x + 24, 1, RESIDUES[i], GRAB if takes else DROP))
            inserters.append(ins)
            when[("ins", ins.n)] = t + extra
        when[("pole-top", i)] = t + at(0.2, 3)
    for idx, x in enumerate(reversed(lab_x)):          # upstream first: the science belt runs west
        i = len(lab_x) - 1 - idx
        t = at(2.8 + idx * 0.32, 50 + idx * 8)
        when[("lab", i)] = t
        ins = Inserter(len(inserters), x + 24, 308, -1, "science", True, "science", timing(x + 24, -1, RESIDUES[idx], GRAB))
        inserters.append(ins)
        when[("ins", ins.n)] = t + at(0.1, 1)
        when[("pole-bottom", i)] = t + at(0.2, 3)

    # Simulate four trips; keep the last, after checking it repeats the third.
    upstream = sorted(inserters, key=lambda s: s.cx * belts[s.belt].direction)
    for n in range(4 * PERIOD):
        for b in belts.values():
            b.recording = n >= 3 * PERIOD
            b.step(n)
        for s in upstream:
            s.tick(n, belts[s.belt])
        if n >= 2 * PERIOD:
            for b in belts.values():
                for k in range(SLOTS):
                    b.history[k].append(b.present[k])
            for s in inserters:
                s.history.append(s.holding)
    for b in belts.values():
        for k in range(SLOTS):
            assert b.history[k][:PERIOD] == b.history[k][PERIOD:], "not periodic"
            b.history[k] = b.history[k][PERIOD:]
    for s in inserters:
        assert s.history[:PERIOD] == s.history[PERIOD:], "not periodic"
        s.history = s.history[PERIOD:]
        empty = sum(1 for c in range(PERIOD // CYCLE) if not any(s.history[c * CYCLE + (s.phase % CYCLE):][:DROP]))
        if empty:
            print(f"inserter {s.n} swings empty {empty} times a loop")
    return belts, inserters, when, asm_x, lab_x


def var(mode, t):
    return f"--d:{t:.2f}s" if mode == "welcome" else f"--t:{t}"


def entity(mode, t, ghost, built):
    return f'<g style="{var(mode, t)}"><g class="fab-gh">{ghost}</g><g class="fab-bu">{built}</g></g>'


def discrete_opacity(visible, extra=""):
    values, times = [], []
    for n, v in enumerate(visible):
        if not values or values[-1] != v:
            values.append(v)
            times.append(n / PERIOD)
    return (values, f'<animate attributeName="opacity" values="{";".join("1" if v else "0" for v in values)}" '
                    f'keyTimes="{";".join(f"{t:.5f}" for t in times)}" calcMode="discrete" dur="{PERIOD * TICK:g}s" repeatCount="indefinite"/>')


def slot(belt, k, visible):
    """One item slot's trip along its belt; visible: per-tick list, or True for always."""
    u0 = (k * GAP) % LENGTH
    y = belt.y
    start = X0 + u0 if belt.direction > 0 else X1 - u0
    near, far = (X0, X1) if belt.direction > 0 else (X1, X0)
    if u0 == 0:
        motion = f'values="{near} {y};{far} {y}" keyTimes="0;1"'
    else:
        a = (LENGTH - u0) / SPEED / PERIOD
        motion = f'values="{start} {y};{far} {y};{near} {y};{start} {y}" keyTimes="0;{a:.5f};{a + 0.00002:.5f};1"'
    anim = f'<animateTransform attributeName="transform" type="translate" {motion} dur="{PERIOD * TICK:g}s" repeatCount="indefinite"/>'
    opacity = ""
    if visible is not True:
        values, times = [], []
        for n, v in enumerate(visible):
            if not values or values[-1] != v:
                values.append(v)
                times.append(n / PERIOD)
        if values == [False]:
            return ""
        if len(values) > 1:
            opacity = (f'<animate attributeName="opacity" values="{";".join("1" if v else "0" for v in values)}" '
                       f'keyTimes="{";".join(f"{t:.5f}" for t in times)}" calcMode="discrete" dur="{PERIOD * TICK:g}s" repeatCount="indefinite"/>')
        start_opacity = "1" if values[0] else "0"
    else:
        start_opacity = "1"
    return f'<g transform="translate({start} {y})" opacity="{start_opacity}">{anim}{opacity}{ITEMS[belt.item]}</g>'


def belt_items(mode, belt, when):
    """Items on one belt, split by the inserter that acts on them: as simulated once that inserter
    is built, flowing straight past (or, on the gear belt, never there) before."""
    by_actor = {}
    for k in range(SLOTS):
        by_actor.setdefault(belt.actor[k], []).append(k)
    out = []
    for actor, ks in by_actor.items():
        if actor is None:
            out += [slot(belt, k, True if belt.full else [False] * PERIOD) for k in ks]
            continue
        t = when[("ins", actor)]
        out.append(f'<g class="fab-bu" style="{var(mode, t)}">' + "".join(slot(belt, k, belt.history[k]) for k in ks) + "</g>")
        if belt.full:
            out.append(f'<g class="fab-pre" style="{var(mode, t)}">' + "".join(slot(belt, k, True) for k in ks) + "</g>")
    return "".join(out)


def belt_entity(mode, belt, t, items):
    y = belt.y
    path = f"M {0 if belt.direction > 0 else 880} {y} H {880 if belt.direction > 0 else 0}"
    ghost = (f'<path d="M 0 {y - 8} H 880 M 0 {y + 8} H 880" stroke="{GHOST}" stroke-dasharray="5 4"/>')
    built = (f'<rect x="0" y="{y - 8}" width="880" height="16" fill="rgba(20,40,64,0.85)"/>'
             f'<path d="M 0 {y - 8} H 880 M 0 {y + 8} H 880" stroke="{LINE}" stroke-width="1.2"/>'
             f'<path class="fab-slats" d="{path}" stroke="{AMBER}" stroke-opacity="0.3" stroke-width="9" stroke-dasharray="2 10"/>'
             + items)
    return entity(mode, t, ghost, built)


def assembler(x, y, k):
    cx, cy = x + 24, y + 24
    ghost = (f'<rect x="{x}" y="{y}" width="48" height="48" fill="none" stroke="{GHOST}" stroke-dasharray="4 3"/>'
             f'<path d="{gear(cx, cy, 11, 8)}" fill="none" stroke="{GHOST}" stroke-dasharray="3 3"/>')
    built = (f'<rect x="{x}" y="{y}" width="48" height="48" fill="rgba(110,160,230,0.10)" stroke="{LINE}" stroke-width="1.5"/>'
             f'<rect x="{x + 5}" y="{y + 5}" width="38" height="38" fill="none" stroke="{LINE}" stroke-opacity="0.4"/>'
             f'<g class="fab-spin" style="transform-origin:{cx}px {cy}px;animation-delay:-{k * 0.7:.1f}s">'
             f'<path d="{gear(cx, cy, 11, 8)}" fill="none" stroke="{LINE}" stroke-width="1.4"/>'
             f'<circle cx="{cx}" cy="{cy}" r="3" fill="{LINE}"/></g>'
             f'<rect class="fab-lamp" x="{x + 39}" y="{y + 5}" width="5" height="5" fill="{AMBER}" style="animation-delay:-{k * 0.9:.1f}s"/>')
    return ghost, built


def lab(x, y, k):
    cx, cy = x + 24, y + 24
    ghost = f'<path d="{octagon(x, y, 48, 12)}" fill="none" stroke="{GHOST}" stroke-dasharray="4 3"/>'
    built = (f'<path d="{octagon(x, y, 48, 12)}" fill="rgba(110,160,230,0.10)" stroke="{LINE}" stroke-width="1.5"/>'
             f'<circle cx="{cx}" cy="{cy}" r="13" fill="none" stroke="{LINE}" stroke-opacity="0.5"/>'
             f'<circle class="fab-core" cx="{cx}" cy="{cy}" r="7" fill="{CYAN}" style="animation-delay:-{k * 0.8:.1f}s"/>')
    return ghost, built


def inserter(s):
    cx, cy = s.cx, s.cy
    end = cy + 16 * s.pick
    dur = f'dur="{CYCLE * TICK:g}s" begin="{(s.phase + GRAB - CYCLE) * TICK:.2f}s" repeatCount="indefinite"'
    ghost = f'<rect x="{cx - 6}" y="{cy - 6}" width="12" height="12" fill="none" stroke="{GHOST}" stroke-dasharray="3 2"/>'
    swing = (f'<animateTransform attributeName="transform" type="rotate" '
             f'values="0 {cx} {cy};0 {cx} {cy};180 {cx} {cy};180 {cx} {cy};0 {cx} {cy};0 {cx} {cy}" '
             f'keyTimes="0;0.12;0.45;0.62;0.95;1" calcMode="spline" '
             f'keySplines="0 0 1 1;0.45 0 0.55 1;0 0 1 1;0.45 0 0.55 1;0 0 1 1" {dur}/>')
    values, holding = discrete_opacity(s.history)
    carry = f'<g transform="translate({cx} {end})"><g opacity="{1 if values[0] else 0}">{holding}{ITEMS[s.item]}</g></g>' 
    built = (f'<rect x="{cx - 6}" y="{cy - 6}" width="12" height="12" fill="rgba(110,160,230,0.15)" stroke="{LINE}"/>'
             f'<g>{swing}'
             f'<line x1="{cx}" y1="{cy}" x2="{cx}" y2="{end}" stroke="{AMBER}" stroke-width="2.5" stroke-linecap="round"/>'
             f'<line x1="{cx - 4}" y1="{end}" x2="{cx + 4}" y2="{end}" stroke="{AMBER}" stroke-width="2"/>'
             f'{carry}</g>'
             f'<circle cx="{cx}" cy="{cy}" r="2.5" fill="{LINE}"/>')
    return ghost, built


def pole(px, py):
    ghost = f'<rect x="{px - 4}" y="{py - 4}" width="8" height="8" fill="none" stroke="{GHOST}" stroke-dasharray="2 2"/>'
    built = (f'<rect x="{px - 4}" y="{py - 4}" width="8" height="8" fill="rgba(110,160,230,0.2)" stroke="{LINE}"/>'
             f'<path d="M {px - 4} {py - 4} L {px + 4} {py + 4} M {px + 4} {py - 4} L {px - 4} {py + 4}" stroke="{LINE}" stroke-width="0.8"/>')
    return ghost, built


def wire(a, b):
    return f'<path d="M {a[0]} {a[1]} Q {(a[0] + b[0]) / 2} {(a[1] + b[1]) / 2 + 9} {b[0]} {b[1]}" fill="none" stroke="{AMBER}" stroke-opacity="0.55"/>'


def drone(path, dur, begin):
    motion = f'<animateMotion dur="{dur}s" begin="{begin}s" repeatCount="indefinite" rotate="auto" path="{path}"/>'
    shadow = f'<g transform="translate(16 22)"><ellipse rx="8" ry="5" fill="rgba(0,0,0,0.28)">{motion}</ellipse></g>'
    body = (f'<g>{motion}<rect x="-6" y="-4.5" width="12" height="9" rx="2" fill="{LINE}"/>'
            + "".join(f'<circle cx="{dx}" cy="{dy}" r="3.2" fill="none" stroke="{LINE}"/>' for dx in (-6, 6) for dy in (-6, 6))
            + f'<circle cx="6" cy="0" r="1.6" fill="{AMBER}"/></g>')
    return shadow + body


def scene(mode):
    belts, inserters, when, asm_x, lab_x = build(mode)
    welcome = mode == "welcome"
    parts = ['<defs>'
             '<radialGradient id="fab-bg" cx="50%" cy="50%" r="75%"><stop offset="0" stop-color="#143352"/><stop offset="1" stop-color="#091726"/></radialGradient>'
             '<pattern id="fab-minor" width="16" height="16" patternUnits="userSpaceOnUse"><path d="M 16 0 H 0 V 16" fill="none" stroke="rgba(120,170,230,0.09)"/></pattern>'
             '<pattern id="fab-major" width="64" height="64" patternUnits="userSpaceOnUse"><path d="M 64 0 H 0 V 64" fill="none" stroke="rgba(120,170,230,0.18)"/></pattern>'
             '<radialGradient id="fab-vignette" cx="50%" cy="50%" r="70%"><stop offset="0.35" stop-color="#050C14" stop-opacity="0.55"/><stop offset="0.6" stop-color="#050C14" stop-opacity="0"/><stop offset="1" stop-color="#050C14" stop-opacity="0.6"/></radialGradient>'
             '</defs>',
             '<rect width="880" height="400" fill="url(#fab-bg)"/><rect width="880" height="400" fill="url(#fab-minor)"/><rect width="880" height="400" fill="url(#fab-major)"/>']
    for name, t in (("plate", (0.2, 2)), ("gear", (0.3, 3)), ("science", (0.4, 4))):
        t = t[0] if welcome else t[1]
        parts.append(belt_entity(mode, belts[name], t, belt_items(mode, belts[name], when)))
    for i, x in enumerate(asm_x):
        parts.append(entity(mode, when[("asm", i)], *assembler(x, 36, i)))
    for i, x in enumerate(lab_x):
        parts.append(entity(mode, when[("lab", i)], *lab(x, 316, i)))
    for s in inserters:
        parts.append(entity(mode, when[("ins", s.n)], *inserter(s)))
    for row, y, key in (([x + 92 for x in asm_x[:-1]], 60, "pole-top"), ([x + 92 for x in lab_x[:-1]], 340, "pole-bottom")):
        for i, px in enumerate(row):
            # A pole and its wire to the pole on its left, both built with the later building.
            t = max(when[(key, i)], when[(key, i + 1)]) if key == "pole-bottom" else when[(key, i)]
            parts.append(entity(mode, t, *pole(px, y)))
            if i > 0:
                t_wire = max(t, max(when[(key, i - 1)], when[(key, i)]) if key == "pole-bottom" else when[(key, i - 1)])
                parts.append(entity(mode, t_wire, "", wire((row[i - 1], y), (px, y))))
    parts.append(drone("M -40 210 C 220 30, 520 390, 920 170", 15, -2))
    parts.append(drone("M 920 340 C 640 190, 300 430, -40 70", 19, -9))
    parts.append('<rect width="880" height="400" fill="url(#fab-vignette)"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" class="fab-scene fab-{mode}" viewBox="0 0 880 400" '
            f'preserveAspectRatio="xMidYMid slice" aria-hidden="true"><style>{CSS.strip()}</style>' + "".join(parts) + "</svg>")


CSS = """
.fab-spin{animation:fabSpin 6s linear infinite}
@keyframes fabSpin{to{transform:rotate(360deg)}}
.fab-lamp{animation:fabPulse 2.4s ease-in-out infinite}
@keyframes fabPulse{0%,100%{opacity:.3}50%{opacity:1}}
.fab-core{animation:fabCore 3s ease-in-out infinite}
@keyframes fabCore{0%,100%{opacity:.35}50%{opacity:.95}}
.fab-slats{animation:fabSlats .4s linear infinite}
@keyframes fabSlats{to{stroke-dashoffset:-12}}
.fab-welcome .fab-gh{opacity:0;animation:fabGhost 1.1s ease-out var(--d) both}
.fab-welcome .fab-bu{opacity:0;animation:fabBuild .45s ease-out calc(var(--d) + .8s) both}
.fab-welcome .fab-pre{animation:fabUnbuild .45s ease-out calc(var(--d) + .8s) both}
@keyframes fabGhost{0%{opacity:0}25%{opacity:1}75%{opacity:1}100%{opacity:0}}
@keyframes fabBuild{from{opacity:0}to{opacity:1}}
@keyframes fabUnbuild{from{opacity:1}to{opacity:0}}
.fab-import .fab-gh{opacity:calc(1 - clamp(0, calc((var(--p) - var(--t)) / 2), 1))}
.fab-import .fab-bu{opacity:clamp(0, calc((var(--p) - var(--t)) / 2), 1)}
.fab-import .fab-pre{opacity:calc(1 - clamp(0, calc((var(--p) - var(--t)) / 2), 1))}
"""

def plan():
    """The simulation and build order for build.js: which slots hold items when, what each inserter
    holds, and the import percent that builds each piece."""
    belts, inserters, when, asm_x, lab_x = build("import")
    top = [when[("pole-top", i)] for i in range(len(asm_x) - 1)]
    bottom = [max(when[("pole-bottom", i)], when[("pole-bottom", i + 1)]) for i in range(len(lab_x) - 1)]
    bits = lambda seq: "".join("1" if v else "0" for v in seq)
    return {
        "tick": TICK, "cycle": CYCLE, "period": PERIOD, "speed": SPEED, "gap": GAP, "x0": X0, "x1": X1, "slots": SLOTS,
        "build": {"belts": {"plate": 2, "gear": 3, "science": 4},
                  "assemblers": [when[("asm", i)] for i in range(len(asm_x))],
                  "labs": [when[("lab", i)] for i in range(len(lab_x))],
                  "inserters": [when[("ins", s.n)] for s in inserters],
                  "poles": top + bottom},
        "belts": {name: {"y": b.y, "direction": b.direction, "full": b.full, "item": b.item, "actor": b.actor,
                         "visible": [bits(h) for h in b.history]} for name, b in belts.items()},
        "inserters": [{"cx": s.cx, "cy": s.cy, "pick": s.pick, "phase": s.phase, "item": s.item, "holding": bits(s.history)}
                      for s in inserters],
        "assemblers": [x + 24 for x in asm_x], "assemblerY": 60,
        "labs": [x + 24 for x in lab_x], "labY": 340,
        "poles": [[x + 92, 60] for x in asm_x[:-1]] + [[x + 92, 340] for x in lab_x[:-1]],
    }


if __name__ == "__main__":
    out = sys.argv[1]
    open(f"{out}/blueprint.svg", "w").write(scene("welcome"))
    json.dump(plan(), open(f"{out}/build-sim.json", "w"), separators=(",", ":"))
    print("blueprint.svg and build-sim.json written")
