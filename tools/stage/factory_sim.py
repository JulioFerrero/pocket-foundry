"""The launcher's working factory (Home, Paused, Settings), drawn by stage/Factory.kt from
the player's own Factorio sprites. This plays the belts and inserters ahead of time: plates come in
on a belt, inserters feed them into assembling machines, output inserters put gears on the belt
above; below, inserters feed science from a belt into labs. An inserter takes one real item off its
belt. Everything repeats every 288 frames (30 fps, 9.6 s).

python3 tools/stage/factory_sim.py app/src/main/assets/stage/factory-sim.json"""
import json
import random
import sys

T = 64                      # pixels per tile: high-res sprites 1:1
W, H = 1760, 800            # 27.5 x 12.5 tiles
FRAMES = 288
CYCLE = 48                  # frames per inserter swing
SPEED = 4                   # px per frame: a yellow belt's 1.875 tiles/s at 30 fps
GAP = 16                    # px between items, alternating lanes: 2 per lane per tile
GRAB, DROP = 44, 20         # swing frames: the hand closes, the hand opens

random.seed(11)
ground = [random.randrange(16) for _ in range(0, H, 256) for _ in range(0, W, 256)]
ASSEMBLERS = [3.5, 7.5, 11.5, 15.5, 19.5, 23.5, 27.5]          # x; rows 2-4
LABS = [11.5, 15.5, 19.5, 23.5]                                 # x; rows 10-12
poles = [(x - 2.0, 3.5) for x in ASSEMBLERS[1:]] + [(x + 2.0, 11.2) for x in LABS[:-1]]
# Inserters: (x, y, pickup side, item, phase). North-facing ones lift from the south.
inserters = ([(x, 5.5, "south", "plate", random.randrange(48)) for x in ASSEMBLERS]
             + [(x, 1.5, "south", "gear", random.randrange(48)) for x in ASSEMBLERS]
             + [(x, 9.5, "north", "science", random.randrange(48)) for x in LABS])
asm_start = [random.randrange(64) for _ in ASSEMBLERS]
lab_start = [random.randrange(33) for _ in LABS]
# (direction index: 0 north, clockwise; y in tiles; phase): 3.2 tiles/s, the real speed.
robots = [(12, 4.8, 0.0), (12, 9.4, 0.55), (4, 7.6, 0.3)]


class Belt:
    """Item slots moving at belt speed; an inserter takes an item out or puts one in."""
    def __init__(self, y, direction, full):
        self.y, self.direction, self.full = y, direction, full
        self.changes = {}                            # slot -> [(frame, present)]

    def x(self, slot, f):
        d = slot * GAP + SPEED * f
        return d if self.direction > 0 else W - d

    def present(self, slot, f):
        value = self.full
        for frame, v in self.changes.get(slot, ()):
            if frame <= f:
                value = v
        return value

    def take(self, at, f, want):
        """The slot nearest x=at holding (want=True) or missing (want=False) an item, flipped."""
        slots = range(-300, 500)
        for reach in (GAP / 2, GAP):
            near = [i for i in slots if abs(self.x(i, f) - at) <= reach and self.present(i, f) == want]
            if near:
                i = min(near, key=lambda i: abs(self.x(i, f) - at))
                self.changes.setdefault(i, []).append((f, not want))
                return True
        return False


belts = {"gear": Belt(0.5, 1, False), "plate": Belt(6.5, 1, True), "science": Belt(8.5, -1, True)}
held = set()                                          # (inserter, frame it grabbed)
for f in range(-3 * FRAMES, FRAMES):
    for n, (x, y, pickup, item, phase) in sorted(enumerate(inserters), key=lambda a: a[1][0] * (1 if a[1][3] != "science" else -1)):
        c = (f + phase) % CYCLE
        if item == "gear":                            # takes from the assembler, drops on the gear belt
            if c == GRAB:
                held.add((n, f))
            elif c == DROP:
                belts["gear"].take(x * T + 2, f, False)
        elif c == GRAB and belts[item].take(x * T + 2, f, True):
            held.add((n, f))

# A seamless loop: the belt one loop later is the same belt shifted by a whole number of slots.
shift = SPEED * FRAMES // GAP
for name, b in belts.items():
    for f in range(FRAMES):
        for i in range(-80, 120):
            assert b.present(i, f) == b.present(i + shift, f - FRAMES), (name, i, f)


def holding(n, f):
    c = (f + inserters[n][4]) % CYCLE
    if GRAB > c >= DROP:
        return False
    grabbed_at = f - (c - GRAB) if c >= GRAB else f - c - (CYCLE - GRAB)
    return (n, grabbed_at) in held


# The slots ever on screen, each as [present at frame 0, frames where it flips...].
first, last = (-40 - SPEED * (FRAMES - 1)) // GAP, (W + 40) // GAP + 1
data = {
    "W": W, "H": H, "T": T, "frames": FRAMES, "cycle": CYCLE, "speed": SPEED, "gap": GAP,
    "ground": ground, "assemblers": ASSEMBLERS, "labs": LABS, "poles": poles,
    "asmStart": asm_start, "labStart": lab_start, "robots": robots,
    "inserters": [{"x": x, "y": y, "pickup": p, "item": item, "phase": phase,
                   "holding": "".join("1" if holding(n, f) else "0" for f in range(FRAMES))}
                  for n, (x, y, p, item, phase) in enumerate(inserters)],
    "belts": {name: {"y": b.y, "direction": b.direction, "first": first,
                     "slots": [[int(b.present(i, 0))] + [f for f in range(1, FRAMES) if b.present(i, f) != b.present(i, f - 1)]
                               for i in range(first, last)]}
              for name, b in belts.items()},
}
json.dump(data, open(sys.argv[1], "w"), separators=(",", ":"))
print("factory-sim.json", len(json.dumps(data, separators=(",", ":"))), "bytes")
