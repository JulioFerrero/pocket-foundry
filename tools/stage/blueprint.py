"""Pocket Foundry's own blueprint factory (no Factorio assets), drawn by the launcher's stage
(app/src/main/java/app/pocketfoundry/stage/Blueprint.kt) on the Welcome and Importing screens.

Plates come in from the left; inserters lift them into assemblers, output inserters put gears on
the top belt. Science comes in from the right; inserters feed it into labs. The belts are simulated:
an inserter takes one real item off its belt, so the belt thins out downstream.

Welcome: buildings are placed as ghosts and built one after another (each entity's "welcome", in s).
Import:  each building is built when the import percent passes its threshold ("import"). Until an
         inserter is built, the items it would take flow past it.

python3 tools/stage/blueprint.py app/src/main/assets/stage   writes blueprint.json"""
import json
import sys

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


def plan():
    """Everything the stage draws: where each piece is, when it's built in either mode, which belt
    slots hold an item at each tick of the loop and what each inserter holds."""
    belts, inserters, when, asm_x, lab_x = build("welcome")
    _, _, at_percent, _, _ = build("import")
    bits = lambda seq: "".join("1" if v else "0" for v in seq)
    times = lambda key: {"welcome": round(when[key], 2), "import": at_percent[key]}
    belt_times = {"plate": (0.2, 2), "gear": (0.3, 3), "science": (0.4, 4)}

    def pole_row(key, xs, y):
        poles, wires = [], []
        for i, px in enumerate(xs):
            # A pole and its wire to the pole on its left, both built with the later building.
            def t(table):
                return max(table[(key, i)], table[(key, i + 1)]) if key == "pole-bottom" else table[(key, i)]
            def t_wire(table):
                before = max(table[(key, i - 1)], table[(key, i)]) if key == "pole-bottom" else table[(key, i - 1)]
                return max(t(table), before)
            poles.append({"x": px, "y": y, "welcome": round(t(when), 2), "import": t(at_percent)})
            if i > 0:
                wires.append({"a": [xs[i - 1], y], "b": [px, y], "welcome": round(t_wire(when), 2), "import": t_wire(at_percent)})
        return poles, wires

    top_poles, top_wires = pole_row("pole-top", [x + 92 for x in asm_x[:-1]], 60)
    bottom_poles, bottom_wires = pole_row("pole-bottom", [x + 92 for x in lab_x[:-1]], 340)
    return {
        "tick": TICK, "cycle": CYCLE, "period": PERIOD, "speed": SPEED, "gap": GAP, "x0": X0, "x1": X1, "grab": GRAB,
        "belts": [{"item": name, "y": b.y, "direction": b.direction, "full": b.full,
                   "welcome": belt_times[name][0], "import": belt_times[name][1],
                   # Per slot: the inserter that acts on it (-1: none) and, if one does, whether it holds an item at each tick.
                   "actors": [-1 if a is None else a for a in b.actor],
                   "visible": [bits(h) if a is not None else "" for a, h in zip(b.actor, b.history)]}
                  for name, b in belts.items()],
        "assemblers": [{"x": x, "y": 36, **times(("asm", i))} for i, x in enumerate(asm_x)],
        "labs": [{"x": x, "y": 316, **times(("lab", i))} for i, x in enumerate(lab_x)],
        "inserters": [{"cx": s.cx, "cy": s.cy, "pick": s.pick, "phase": s.phase, "item": s.item, "holding": bits(s.history),
                       **times(("ins", s.n))} for s in inserters],
        "poles": top_poles + bottom_poles,
        "wires": top_wires + bottom_wires,
        # Drones fly a cubic curve at an even speed: start, two controls, end; seconds a trip; when the first began.
        "drones": [{"path": [-40, 210, 220, 30, 520, 390, 920, 170], "duration": 15, "begin": -2},
                   {"path": [920, 340, 640, 190, 300, 430, -40, 70], "duration": 19, "begin": -9}],
    }


if __name__ == "__main__":
    json.dump(plan(), open(f"{sys.argv[1]}/blueprint.json", "w"), separators=(",", ":"))
    print("blueprint.json written")
