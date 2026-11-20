"""
The on-screen controls as pre-rendered sprites, eighth pass: the seventh's look (Factorio's own interface as a real
painted-plastic object, in the app's GUI palette), with presses that read as a cap moving, not as a light changing.

  - Caps are tall and their walls spread towards the foot, so from straight above the walls show all round. Each
    comes up through a hole in its panel; pressed, it goes 7.5 mm down, to well below the panel's face.
  - Each control is drawn as an eye 16 cm straight above its own middle sees it (the camera itself looks straight
    down and is flat; the parts are drawn in perspective about their own control). So circles stay round and centred
    and nothing leans, yet a cap going down shrinks, and its lit wall gives way to the dark gap of its hole.
  - The light is the last thing: the cap stops dead at the bottom, then turns orange and the glow comes up.
    Let go, it springs back a little past its rest and settles: the release has frames of its own.
  - A, B, X and Y are one size, each in a round bezel of its own: no plate, the game shows between them.
  - The D-pad is one cross that rocks: the pressed arm goes down, the one across comes up. Eight ways.
  - Every control is a still base and, over it, a strip of the cap's frames (the cap, its shadow on the base, its glow),
    rendered at the size the app draws on a 3168x1440 phone (3150 px per metre), not larger.

  - The toolbar (a panel in three pieces that join for any number of keys, and a key strip per tool) and the FPS
    counter's panel (at twice the size, with a sheet of glass to lay over what the app draws) are made the same way.

  blender -b -P controls.py -- [--out DIR] [--only SCENE,SCENE] [--samples N] [--quick]
SCENE is a control's name, or tools (the toolbar's keys and panel), toolbar (the panel alone), fps.
Writes DIR/<name>.png (a base, or a cap's strip of frames side by side) and DIR/sprites.json.
One render at a time (two run out of GPU memory); PF_CPU=1 renders on the processor.
"""
import bpy
import bmesh
import json
import math
import os
import random
import sys
import zlib
from mathutils import Quaternion, Vector

# The website's Blender kit (pocket-foundry-site's art/: blender_kit.py, factory.py), beside this repository by default.
ART = os.environ.get('PF_SITE_ART', os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', '..', 'pocketfoundry-site', 'art'))
FONT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'res', 'font', 'titillium_web_bold.ttf')
sys.path.insert(0, ART)
import blender_kit as kit  # noqa: E402
import factory  # noqa: E402
from factory import box, lathe, group, inside, shape  # noqa: E402
from blender_kit import link  # noqa: E402

argv = sys.argv[sys.argv.index('--') + 1:] if '--' in sys.argv else []
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = argv[argv.index('--out') + 1] if '--out' in argv else os.path.join(HERE, 'sprites')
ONLY = set(argv[argv.index('--only') + 1].split(',')) if '--only' in argv else None
SAMPLES = int(argv[argv.index('--samples') + 1]) if '--samples' in argv else 384
QUICK = '--quick' in argv     # four frames of each cap, for a look
FPS = 60
PPM = 3150           # pixels per metre: a 1440 px tall screen is 0.457 m, and the stick's base (0.28 of it) 0.128 m
SUN_TILT = 58        # the sun's angle from straight up, degrees: low, so a bevel that faces it is far lighter than a face
SUN_TURN = -160      # it shines down the screen and a little to the right: light top edges, dark bottom edges
SKY = 0.26           # the even light from everywhere, as a share of what a flat face gets
MARGIN_NEAR = 0.008  # room kept left of and above a control
MARGIN_FAR = 0.030   # and right of and below it, where its shadow falls
os.makedirs(OUT, exist_ok=True)

scene = kit.start()

# ---------------------------------------------------------------- the stage

ground = bpy.data.objects.new('ground', bpy.data.meshes.new('ground'))
link(ground)
bm = bmesh.new()
bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=2)
bm.to_mesh(ground.data)
bm.free()
ground.is_shadow_catcher = True

sun = bpy.data.lights.new('sun', 'SUN')
sun.energy = (1 - SKY) * math.pi / math.cos(math.radians(SUN_TILT))   # so a flat matte face gets 1 - SKY from it
sun.angle = math.radians(12)
sun_object = link(bpy.data.objects.new('sun', sun))
sun_object.rotation_euler = (math.radians(SUN_TILT), 0, math.radians(SUN_TURN))

world = bpy.data.worlds.new('even')
scene.world = world
world.use_nodes = True
world.node_tree.nodes['Background'].inputs['Color'].default_value = (1, 1, 1, 1)
world.node_tree.nodes['Background'].inputs['Strength'].default_value = SKY

# A soft box straight overhead, seen only in reflections: what a flat face mirrors back to the camera. A matte face
# spreads it to nothing, a polished one shows it: that is how the thumb-worn centres read as shinier.
SOFTBOX = float(os.environ.get('PF_SOFTBOX', '0.5'))
softbox = bpy.data.objects.new('softbox', bpy.data.meshes.new('softbox'))
link(softbox)
bm = bmesh.new()
bmesh.ops.create_grid(bm, x_segments=1, y_segments=1, size=0.55)
bm.to_mesh(softbox.data)
bm.free()
softbox.location = (0, 0.25, 6)
lit = bpy.data.materials.new('softbox')
lit.use_nodes = True
lit.node_tree.nodes.remove(lit.node_tree.nodes['Principled BSDF'])
emit = lit.node_tree.nodes.new('ShaderNodeEmission')
emit.inputs['Strength'].default_value = SOFTBOX
lit.node_tree.links.new(emit.outputs[0], lit.node_tree.nodes['Material Output'].inputs['Surface'])
softbox.data.materials.append(lit)
softbox.visible_camera = False
softbox.visible_diffuse = False
softbox.visible_shadow = False

camera_data = bpy.data.cameras.new('camera')
camera_data.type = 'ORTHO'
camera = link(bpy.data.objects.new('camera', camera_data))  # unrotated: it looks straight down
scene.camera = camera

kit.cycles(SAMPLES, 100, 100, 100)
scene.render.film_transparent = True
scene.render.image_settings.color_mode = 'RGBA'
scene.view_settings.view_transform = 'Standard'
scene.view_settings.look = 'None'
scene.view_settings.exposure = 0.0
scene.cycles.filter_width = 1.3

font = bpy.data.fonts.load(FONT)


# ---------------------------------------------------------------- shapes from above

def circle(cx, cy, r):
    return lambda x, y: math.hypot(x - cx, y - cy) - r


def rbox(cx, cy, half_w, half_h, r, turn=0.0):
    """A rounded rectangle's signed distance; [turn] in degrees."""
    c, s = math.cos(math.radians(turn)), math.sin(math.radians(turn))

    def distance(x, y):
        x, y = x - cx, y - cy
        x, y = x * c + y * s, -x * s + y * c
        qx, qy = abs(x) - half_w + r, abs(y) - half_h + r
        return math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - r
    return distance


def union(*shapes, blend=0.0):
    """The shapes together; [blend] fillets where they meet."""
    def distance(x, y):
        d = shapes[0](x, y)
        for other in shapes[1:]:
            e = other(x, y)
            if blend > 0:
                h = max(blend - abs(d - e), 0) / blend
                d = min(d, e) - h * h * blend / 4
            else:
                d = min(d, e)
        return d
    return distance


def outline(sdf, reach=0.2):
    """A radius function for a shape that holds its own middle: how far out its edge is at an angle, inset or not."""
    def radius(angle, inset=0.0):
        dx, dy = math.cos(angle), math.sin(angle)
        low, high = 0.0, reach
        for _ in range(26):
            mid = (low + high) / 2
            if sdf(dx * mid, dy * mid) + inset < 0:
                low = mid
            else:
                high = mid
        return low
    return radius


def letters(text, location, size, material, extrude=0.0006, keyline=None, grow=0.0009, squeeze=1.0):
    """Raised letters lying flat, centred on [location]; [keyline] is a material for a dark edge round them."""
    made = []
    for offset, mat, depth in ((grow, keyline, extrude * 0.45), (0.0, material, extrude)):
        if mat is None:
            continue
        curve = bpy.data.curves.new('letters', 'FONT')
        curve.body = text
        curve.font = font
        curve.size = size
        curve.extrude = depth
        curve.offset = offset
        curve.align_x = 'CENTER'
        curve.align_y = 'CENTER'
        curve.bevel_depth = depth * 0.25 if offset == 0 else 0
        curve.materials.append(mat)
        obj = link(bpy.data.objects.new('letters', curve))
        obj.parent = factory._parent[-1]
        obj.location = Vector(location) + Vector((0, 0, depth))
        obj.scale = (squeeze, 1, 1)
        made.append(obj)
    return made


# ---------------------------------------------------------------- solids swept from an outline

RND = random.Random(1)


def polar(radius, degrees, z=0.0):
    a = math.radians(degrees)
    return Vector((math.cos(a) * radius, math.sin(a) * radius, z))


def round_(radius):
    return lambda angle, inset=0.0: radius - inset


def sweep(rings, location, material, count=256, bottom=True, top=True):
    """
    A solid from rings of (outline, inset, height[, wear]), bottom up; an outline is a radius function or a radius.
    [wear] (0 to 1) is kept on the mesh for the paint to read: how much hands rub that ring. [bottom] and [top]
    False leave those ends open.
    """
    mesh = bpy.data.meshes.new('sweep')
    bm = bmesh.new()
    worn = bm.verts.layers.float.new('wear')
    made = []
    for ring_ in rings:
        shape_, inset, height = ring_[:3]
        row = []
        for i in range(count):
            a = i / count * math.tau
            r = shape_(a, inset) if callable(shape_) else shape_ - inset
            v = bm.verts.new((math.cos(a) * r, math.sin(a) * r, height))
            v[worn] = ring_[3] if len(ring_) > 3 else 0.0
            row.append(v)
        made.append(row)
    for lower, upper in zip(made, made[1:]):
        for i in range(count):
            bm.faces.new((lower[i], lower[(i + 1) % count], upper[(i + 1) % count], upper[i]))
    ends = ([(made[0], rings[0][2])] if bottom else []) + ([(made[-1], rings[-1][2])] if top else [])
    for row, height in ends:
        middle = bm.verts.new((0, 0, height))
        for i in range(count):
            bm.faces.new((row[i], row[(i + 1) % count], middle))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.to_mesh(mesh)
    bm.free()
    return factory._place(bpy.data.objects.new('sweep', mesh), material, location, sharp=35)


def rounded(outline_, top, radius, steps=6):
    """The rings of a rounded top edge: from the wall, [radius] below [top], over to the flat. This is what hands rub."""
    rings = [(outline_, radius * (1 - math.cos(k / steps * math.pi / 2)), top - radius + radius * math.sin(k / steps * math.pi / 2), 1.0)
             for k in range(1, steps + 1)]
    return rings + [(outline_, radius + 0.0003, top, 0.25)]   # a narrow ring to end the rounding: the flat stays flat


# ---------------------------------------------------------------- painted plastic, in the palette of GuiSheet.kt

def linear(code):
    srgb = [int(code[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return tuple(c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in srgb)


def lerp(a, b, t):
    return tuple(p + (q - p) * t for p, q in zip(a, b))


def plastic(name, colour, rough=0.55, spec=0.5, worn=None, polish=0.0, reach=0.02, alpha=1.0, coat=0.0, grain=1.0, even=False):
    """
    Painted plastic: a fine grain you can feel, smudges in its sheen, a few hairline scratches, edges rubbed
    towards [worn], and within [reach] of its middle a patch polished by [polish]. Colours are linear RGB.
    A flat, clean stretch of it still renders in the colour given.
    """
    material = bpy.data.materials.new(name)
    material.use_nodes = True
    nodes, links = material.node_tree.nodes, material.node_tree.links
    bsdf = nodes['Principled BSDF']

    def wire(socket, value):
        if isinstance(value, bpy.types.NodeSocket):
            links.new(value, socket)
        else:
            socket.default_value = (*value, 1) if isinstance(value, tuple) and len(value) == 3 else value

    def calc(operation, a, b=0.0, clamp=False):
        n = nodes.new('ShaderNodeMath')
        n.operation, n.use_clamp = operation, clamp
        wire(n.inputs[0], a)
        wire(n.inputs[1], b)
        return n.outputs[0]

    def ramp(value, low, high, to_low=0.0, to_high=1.0):
        n = nodes.new('ShaderNodeMapRange')
        n.interpolation_type = 'SMOOTHSTEP'
        wire(n.inputs[0], value)
        for i, v in enumerate((low, high, to_low, to_high)):
            n.inputs[i + 1].default_value = v
        return n.outputs[0]

    def blend(factor, a, b):
        n = nodes.new('ShaderNodeMix')
        n.data_type = 'RGBA'
        wire(n.inputs[0], factor)
        wire(n.inputs[6], a)
        wire(n.inputs[7], b)
        return n.outputs[2]

    coord = nodes.new('ShaderNodeTexCoord').outputs['Object']

    def noise(scale, detail, vector=coord):
        n = nodes.new('ShaderNodeTexNoise')
        wire(n.inputs['Vector'], vector)
        n.inputs['Scale'].default_value = scale
        n.inputs['Detail'].default_value = detail
        return n.outputs[0]

    smudge = noise(55, 3)                                   # about a thumb across
    stretch = nodes.new('ShaderNodeMapping')
    wire(stretch.inputs['Vector'], coord)
    stretch.inputs['Rotation'].default_value = (0, 0, 0.5)
    stretch.inputs['Scale'].default_value = (2400, 55, 2400)
    scratch = ramp(noise(1, 0, stretch.outputs[0]), 0.80, 0.83)   # long and hair thin, and few
    if even:    # no smudges, no scratches: for a panel cut into pieces that must join without a seam
        smudge, scratch = 0.5, 0.0
    rubbed = nodes.new('ShaderNodeAttribute')
    rubbed.attribute_name = 'wear'
    wear = calc('MULTIPLY', rubbed.outputs[2], ramp(noise(380, 2), 0.35, 0.65, 0.3, 1.0))
    roughness = calc('ADD', rough - 0.07, calc('MULTIPLY', smudge, 0.14))
    roughness = calc('SUBTRACT', roughness, calc('MULTIPLY', wear, 0.20))
    roughness = calc('SUBTRACT', roughness, calc('MULTIPLY', scratch, 0.16))
    if polish:
        flat = nodes.new('ShaderNodeVectorMath')
        flat.operation = 'MULTIPLY'
        wire(flat.inputs[0], coord)
        flat.inputs[1].default_value = (1, 1, 0)
        away = nodes.new('ShaderNodeVectorMath')
        away.operation = 'LENGTH'
        wire(away.inputs[0], flat.outputs[0])
        thumb = calc('MULTIPLY', ramp(away.outputs[1], reach * 0.15, reach, 1.0, 0.0), ramp(smudge, 0.25, 0.6, 0.55, 1.0))
        roughness = calc('SUBTRACT', roughness, calc('MULTIPLY', thumb, polish))
    roughness = calc('MAXIMUM', roughness, 0.12)
    shade = blend(smudge, tuple(c * 0.97 for c in colour), tuple(c * 1.03 for c in colour))
    if worn:
        shade = blend(calc('MULTIPLY', wear, 0.40), shade, worn)
    wire(bsdf.inputs['Base Color'], shade)
    wire(bsdf.inputs['Roughness'], roughness)
    bsdf.inputs['Specular IOR Level'].default_value = spec
    bsdf.inputs['Coat Weight'].default_value = coat
    bsdf.inputs['Coat Roughness'].default_value = 0.18
    bsdf.inputs['Alpha'].default_value = alpha
    if grain:
        bump = nodes.new('ShaderNodeBump')
        bump.inputs['Strength'].default_value = 0.30 * grain
        bump.inputs['Distance'].default_value = 0.00004
        wire(bump.inputs['Height'], noise(1500, 2))        # two thirds of a millimetre: two pixels on the phone
        links.new(bump.outputs[0], bsdf.inputs['Normal'])
    return material


def glow_paint(amount):
    """The soft light round a pressed button: brightest at the button, fading to nothing; [amount] 0 to 1."""
    material = bpy.data.materials.new('glow')
    material.use_nodes = True
    nodes, links = material.node_tree.nodes, material.node_tree.links
    nodes.remove(nodes['Principled BSDF'])
    fade = nodes.new('ShaderNodeAttribute')
    fade.attribute_name = 'wear'
    power = nodes.new('ShaderNodeMath')
    power.operation = 'POWER'
    links.new(fade.outputs[2], power.inputs[0])
    power.inputs[1].default_value = 1.5
    strength = nodes.new('ShaderNodeMath')
    strength.operation = 'MULTIPLY'
    links.new(power.outputs[0], strength.inputs[0])
    strength.inputs[1].default_value = amount
    light = nodes.new('ShaderNodeEmission')
    light.inputs['Color'].default_value = (*linear('#F0BE72'), 1)
    light.inputs['Strength'].default_value = 1.15
    clear_ = nodes.new('ShaderNodeBsdfTransparent')
    mix = nodes.new('ShaderNodeMixShader')
    links.new(strength.outputs[0], mix.inputs[0])
    links.new(clear_.outputs[0], mix.inputs[1])
    links.new(light.outputs[0], mix.inputs[2])
    links.new(mix.outputs[0], nodes['Material Output'].inputs['Surface'])
    return material


PANEL = plastic('panel', linear('#313031'), rough=0.60, spec=0.35, worn=linear('#5E5C5D'))
EDGE = plastic('edge', linear('#141314'), rough=0.6, spec=0.3)        # the dark line round every panel and button
INSET = plastic('inset', linear('#242324'), rough=0.68, spec=0.3)     # a well's floor
PANEL_EVEN = plastic('panel even', linear('#313031'), rough=0.60, spec=0.35, worn=linear('#5E5C5D'), even=True)
INSET_EVEN = plastic('inset even', linear('#242324'), rough=0.68, spec=0.3, even=True)
SCREEN = plastic('screen', linear('#0D0F10'), rough=0.22, spec=0.12, grain=0, even=True)   # a display, switched off: dark and glassy
GLASS = plastic('smoked', linear('#1C1B1C'), rough=0.30, spec=0.5, alpha=0.70, grain=0)   # the floor of a wide well: smoked, see-through
LINE = plastic('line', linear('#8A8887'), rough=0.5, grain=0)         # the thin light lines in a well
STUD = plastic('stud', linear('#414040'), rough=0.6, spec=0.35, worn=linear('#6E6C6C'))
INK = plastic('ink', linear('#050505'), rough=0.38, grain=0)          # printed lettering, a little glossier than the cap
WHITE = plastic('white', linear('#F4F4F4'), rough=0.38, grain=0)
TONES = {  # a cap's paint, and what its edges rub to (the palette's own light tone of it)
    'grey': ('#8E8E8E', '#E3E3E3'), 'green': ('#5EB663', '#95DF99'), 'red': ('#E8605A', '#F5A29E'),
    'orange': ('#E39C40', '#F7CC8F'), 'dark': ('#454445', '#8A8889'),
    # an IBM keyboard's two greys: the letters' pale one and the modifiers' darker one
    'pearl': ('#C4C2BC', '#ECEAE5'), 'slate': ('#555452', '#8C8A86'),
}

TOP = 0.0050      # a panel's face
WELL = 0.0018     # the floor of a wide well
SHELF = 0.0038    # the floor of the shallow well round a cap's hole
FLOOR_W = 0.0018  # and how wide that floor is
PROUD = 0.0050    # how far a cap stands above its panel's face at rest
TRAVEL = 0.0075   # how far it goes down: pressed, it is well below the face
TAPER = 0.5       # how much a cap's walls spread, per unit of height
BEVEL = 0.0014    # the radius of a cap's rounded edge
LINE_W = 0.0008   # the dark line round things


def cap_paint(tone, glow, reach):
    """A cap's paint: its own tone at rest, the palette's orange when fully lit."""
    face, light = (linear(c) for c in TONES[tone])
    lit_face, lit_light = (linear(c) for c in TONES['orange'])
    return plastic(tone, lerp(face, lit_face, glow), rough=0.50, worn=lerp(light, lit_light, glow), polish=0.24, reach=reach,
                   coat=0.15)


def _ancestors(o):
    seen = []
    while o.parent:
        o = o.parent
        seen.append(o)
    return seen


def members(holder):
    return [o for o in bpy.data.objects if o == holder or holder in _ancestors(o)]


def clear(holder):
    for o in members(holder):
        bpy.data.objects.remove(o, do_unlink=True)


# ---------------------------------------------------------------- the interface's pieces

LENS = 0.16       # how far above a control's face the eye is: near enough that a cap going down visibly shrinks


class pose:
    """
    Parts made within are placed at ([x], [y], [z]), turned by [tilt] (an axis and degrees), and then drawn as an eye
    [LENS] above that spot sees them: what is nearer than a panel's face is larger, what is deeper is smaller. Every
    control has its own eye straight above it, so circles stay round and centred, and nothing leans to one side.
    """
    def __init__(self, x=0.0, y=0.0, z=0.0, tilt=None):
        self.at, self.z, self.tilt = (x, y), z, tilt

    def __enter__(self):
        self.root = factory._parent[-1]
        self.holder = group('pose', location=(*self.at, self.z))
        if self.tilt:
            self.holder.rotation_mode = 'QUATERNION'
            self.holder.rotation_quaternion = Quaternion(self.tilt[0], math.radians(self.tilt[1]))
        factory._parent.append(self.holder)
        return self

    def __exit__(self, *_):
        factory._parent.pop()
        bpy.context.view_layer.update()
        graph = bpy.context.evaluated_depsgraph_get()
        cx, cy = self.at
        base = self.root.matrix_world.inverted()
        for old in members(self.holder):
            if old.type == 'EMPTY':
                continue
            mesh = bpy.data.meshes.new_from_object(old.evaluated_get(graph))
            mesh.transform(base @ old.matrix_world)
            for v in mesh.vertices:
                s = (LENS - TOP) / (LENS - v.co.z)
                v.co.x, v.co.y = (v.co.x - cx) * s, (v.co.y - cy) * s      # and the part's own middle is the spot's
            new = link(bpy.data.objects.new(old.name, mesh))
            new.parent = self.root
            new.location = (cx, cy, 0)
            for flag in ('visible_shadow', 'visible_diffuse', 'visible_glossy', 'visible_transmission'):
                setattr(new, flag, getattr(old, flag))
            bpy.data.objects.remove(old, do_unlink=True)
        bpy.data.objects.remove(self.holder, do_unlink=True)


def rim(outer, count):
    """A panel's outside: its dark border, wall and rounded edge, as rings up to its face."""
    sweep([(outer, -LINE_W, 0), (outer, -LINE_W, TOP - 0.0012), (outer, 0.0004, TOP - 0.0012)], (0, 0, 0), EDGE, count, bottom=False, top=False)
    return [(outer, 0, 0), (outer, 0, TOP - 0.0010, 0.4)] + rounded(outer, TOP, 0.0010, 5)


def panel(outer, well, count=512, well_z=WELL):
    """A panel round a wide well with a floor of smoked glass, see-through: the stick's field, the pad."""
    floor = sweep([(well, -0.0002, well_z)], (0, 0, 0), GLASS, count, bottom=False)
    floor.visible_shadow = floor.visible_diffuse = floor.visible_glossy = floor.visible_transmission = False   # only the camera sees it
    depth = TOP - well_z
    slope = depth * 0.45
    rings = rim(outer, count) + [(well, -slope - 0.0006, TOP, 0.25), (well, -slope - 0.0002, TOP - 0.00005, 1.0),
                                 (well, -slope * 0.95, TOP - depth * 0.05, 1.0), (well, -slope * 0.86, TOP - depth * 0.14, 0.5), (well, 0, well_z)]
    return sweep(rings, (0, 0, 0), PANEL, count, bottom=False, top=False)


def socket(hole, lead, count=512, face=None, floor=None):
    """
    A shallow dark well and, in it, the hole a cap comes up through: its walls go straight down to a dark floor.
    [lead] is the rings of the panel's face up to the well. From above, the wall nearest the sun is in shadow and
    the far one is lit.
    """
    def well(angle, inset=0.0):
        return hole(angle, inset - FLOOR_W)
    depth = TOP - SHELF
    slope = depth * 0.6
    rings = lead + [(well, -slope - 0.0006, TOP, 0.25), (well, -slope - 0.0002, TOP - 0.00005, 1.0),
                    (well, -slope * 0.9, TOP - depth * 0.1, 1.0), (well, 0, SHELF)]
    sweep(rings, (0, 0, 0), face or PANEL, count, bottom=False, top=False)
    sweep([(well, -0.0002, SHELF), (hole, -0.0005, SHELF, 0.3), (hole, -0.0002, SHELF - 0.00008, 1.0), (hole, 0, SHELF - 0.0004, 1.0),
           (hole, 0, 0.0003)], (0, 0, 0), floor or INSET, count, bottom=False, top=False)
    sweep([(hole, -0.001, 0.0003)], (0, 0, 0), EDGE, count, bottom=False)


def bezel(outer, hole, count=512):
    """A panel with one cap's well and hole in it."""
    socket(hole, rim(outer, count), count)


def hole_of(outline_, clearance=0.0004):
    """The hole a cap of [outline_] comes up through: the cap's own section where it meets the well, and a little more."""
    grow = TAPER * (TOP + PROUD - SHELF - BEVEL) + clearance
    return lambda angle, inset=0.0: outline_(angle, inset - grow)


def body(outline_, tone, glow, depth=0.018, crown=0.0, span=0.0, dish=None, reach=0.02, count=256, bottom=False):
    """
    A cap, its top at height nought: a flat face, a rounded edge, and walls that spread as they go down, so that from
    above they show all round. [crown] domes its top over [span]; [dish] (rim, width, depth) hollows it for a thumb.
    """
    rings = [(outline_, -TAPER * (depth - BEVEL), -depth, 0.1), (outline_, 0, -BEVEL, 0.55)] + rounded(outline_, 0.0, BEVEL)
    if crown:
        rings += [(outline_, BEVEL + 0.0003 + f * span, crown * (1 - (1 - f) ** 2)) for f in (0.2, 0.4, 0.6, 0.8, 1.0)]
    if dish:
        edge, width, deep = dish
        rings += [(outline_, BEVEL + edge, 0, 0.5)]
        rings += [(outline_, BEVEL + edge + width * f, -deep * (0.5 - 0.5 * math.cos(math.pi * f))) for f in (0.15, 0.3, 0.5, 0.7, 0.85, 1.0)]
    sweep(rings, (0, 0, 0), cap_paint(tone, glow, reach), count, bottom=bottom)
    return rings[-1][2] + 0.00003


def halo(inner, edge, x, y, amount, z=None, count=256):
    """
    The light that comes up round a cap once it is down: faint over the gap at the cap, brightest at the hole's
    edge, fading to nothing over the panel. A flat ring just over the panel, open in the middle where the cap is.
    """
    z = TOP + 0.0003 if z is None else z
    ring_ = sweep([(inner, 0.0003, z, 0.5), (edge, 0.0006, z, 0.85), (edge, 0, z, 1.0), (edge, -0.0015, z, 0.8), (edge, -0.0030, z, 0.5),
                   (edge, -0.0050, z, 0.2), (edge, -0.0072, z, 0.0)], (x, y, 0), glow_paint(amount), count, bottom=False, top=False)
    ring_.visible_shadow = ring_.visible_diffuse = ring_.visible_glossy = False
    return ring_


def text(body_, x, y, z, size, material=INK):
    return letters(body_, (x, y, z), size, material, extrude=0.00010)


def arrow(x, y, z, turn, size, material=INK):
    """A solid triangle pointing along [turn], as a scroll bar's arrow."""
    points = [(size * 0.62, 0), (-size * 0.45, size * 0.72), (-size * 0.45, -size * 0.72)]
    c, s = math.cos(math.radians(turn)), math.sin(math.radians(turn))
    return shape([(px * c - py * s, px * s + py * c) for px, py in points], (x, y, z + 0.0001), material, normal=(0, 0, 1))


def button(outline_, p, glow, tone, draw, x=0.0, y=0.0, count=256, **shape_):
    """A cap in its hole, [p] of the way down, [glow] of the way lit; [draw](z) puts its sign on it."""
    with pose(x, y, TOP + PROUD - TRAVEL * p):
        draw(body(outline_, tone, glow, count=count, **shape_))
    if glow > 0.01:
        halo(outline_, hole_of(outline_), x, y, glow, count=count)


# ---------------------------------------------------------------- the controls: each a base, and a cap that presses

KNOB = round_(0.0240)


def stick_base():
    """A round panel with a wide round well of smoked glass: the stick's field. Four marks on its rim, a ring on its floor."""
    with pose():
        panel(round_(0.0640), round_(0.0505))
        lathe([(0.0346, WELL), (0.0346, WELL + 0.0002), (0.0332, WELL + 0.0002), (0.0332, WELL)], (0, 0, 0), LINE, segments=160, caps=False)
        for turn in (0, 90, 180, 270):
            with inside(group('mark', rotation=(0, 0, turn))):
                box((0.0052, 0.0016, 0.0002), (0.0584, 0, TOP + 0.0001), INSET, bevel=0)


def stick_knob(p, glow, letter='L'):
    """A round grey cap, hollowed for the thumb, with its letter; it stands high over the well and clicks down (L3)."""
    with pose(0, 0, 0.0125 - 0.0045 * p):
        z = body(KNOB, 'grey', glow, depth=0.0060, dish=(0.0014, 0.0056, 0.0011), reach=0.020, bottom=True)
        if letter:
            text(letter, 0, 0, z, 0.0280)
    if glow > 0.01:
        foot_ = lambda angle, inset=0.0: KNOB(angle, inset - 0.0018)   # noqa: E731
        halo(foot_, foot_, 0, 0, glow, z=WELL + 0.0003)


PAD = (0.192, 0.110)


def trackpad():
    """A wide inset panel of smoked glass: the right stick as a pad. A cross marks its middle."""
    w, h, margin = PAD[0], PAD[1], 0.0075
    with pose():
        panel(outline(rbox(0, 0, w / 2 - LINE_W, h / 2 - LINE_W, 0.0034)), outline(rbox(0, 0, w / 2 - margin, h / 2 - margin, 0.0020)),
              count=2048, well_z=0.0008)
        for bw, bh in ((0.0240, 0.0016), (0.0016, 0.0240)):
            box((bw, bh, 0.0002), (0, 0, 0.0008 + 0.0001), LINE, bevel=0)


FACE = 0.0560                                        # A, B, X and Y are one size: each in a round bezel of its own
FACE_CAP = round_(FACE / 2 - 0.0085)
FACE_TONE = {'a': 'green', 'b': 'red', 'x': 'grey', 'y': 'grey'}


def face_base():
    with pose():
        bezel(round_(FACE / 2), hole_of(FACE_CAP), count=256)


def face_cap(name):
    r = FACE / 2 - 0.0085
    return lambda p, glow: button(FACE_CAP, p, glow, FACE_TONE[name], lambda z: text(name.upper(), 0, 0, z, r * 1.32),
                                  crown=0.0005, span=r - BEVEL - 0.0023, reach=r * 0.85)


DPAD = 0.0914
WAYS = {'right': 0, 'up-right': 45, 'up': 90, 'up-left': 135, 'left': 180, 'down-left': 225, 'down': 270, 'down-right': 315}
CROSS = outline(union(rbox(0, 0, 0.0112, 0.0335, 0.0022), rbox(0, 0, 0.0335, 0.0112, 0.0022), blend=0.0030))
ROCK, SINK = 9.0, 0.0022     # the cross tips by this many degrees, and its middle goes down this far


def dpad_base():
    """A round panel, a shallow cross-shaped well, and the hole the cross rocks in."""
    with pose():
        bezel(round_(DPAD / 2), hole_of(CROSS, 0.0016))


def dpad_cross(way):
    """The D-pad's one cross, rocked towards [way]: that arm goes down, the one across from it comes up."""
    def build(p, glow):
        turn = math.radians(WAYS[way])
        with pose(0, 0, TOP + PROUD - SINK * p, tilt=(Vector((-math.sin(turn), math.cos(turn), 0)), ROCK * p)):
            z = body(CROSS, 'grey', glow, count=768, reach=0.012)
            for a in (0, 90, 180, 270):
                q = polar(0.0232, a)
                arrow(q.x, q.y, z, a, 0.0085)
            lathe([(0.0046, z), (0.0046, z + 0.0001), (0.0038, z + 0.0001), (0.0038, z)], (0, 0, 0), INK, segments=48, caps=False)
        if glow > 0.01:   # the light comes up round the arm that is down: round two, a little less, on a diagonal
            for a in (0, 90, 180, 270):
                near = math.cos(math.radians(a - WAYS[way]))
                if near > 0.5:
                    q = polar(0.0215, a)
                    arm = outline(rbox(0, 0, 0.0120, 0.0112, 0.0022, turn=a))
                    halo(arm, hole_of(arm, 0.0016), q.x, q.y, glow * (1.0 if near > 0.9 else 0.8), z=TOP + 0.0003 + a * 1e-6)   # no two in one plane
    return build


def wide_key(half_w, half_h):
    return outline(rbox(0, 0, half_w - 0.0089, half_h - 0.0089, 0.0016))


def wide_base(half_w, half_h):
    """A narrow panel with the hole for a wide cap: a bumper's or a trigger's."""
    with pose():
        bezel(outline(rbox(0, 0, half_w - LINE_W, half_h - LINE_W, 0.0030)), hole_of(wide_key(half_w, half_h)), count=1024)


def wide_cap(label, half_w, half_h, size):
    return lambda p, glow: button(wide_key(half_w, half_h), p, glow, 'grey', lambda z: text(label, 0, 0, z, size), count=1024,
                                  reach=half_h * 0.9)


SMALL = 0.0387
SMALL_CAP = round_(SMALL / 2 - 0.0080)


def small_base():
    """A small round bezel with the hole for a small dark button."""
    with pose():
        bezel(round_(SMALL / 2), hole_of(SMALL_CAP), count=256)


def small_cap(kind):
    """A small round dark cap with a white sign, as the buttons of a window's title bar are; the sign is dark once it is lit."""
    def build(p, glow):
        sign = plastic('sign', lerp(linear('#F4F4F4'), linear('#141314'), glow), rough=0.38, grain=0)

        def draw(z):
            z += 0.0001
            if kind == 'menu':
                for y in (-0.0038, 0, 0.0038):
                    box((0.0112, 0.0019, 0.0002), (0, y, z), sign, bevel=0)
            elif kind == 'view':
                def frame(cx, cy, lift):
                    for dx, dy, w, h in ((0, 0.0029, 0.0075, 0.0017), (0, -0.0029, 0.0075, 0.0017), (0.0029, 0, 0.0017, 0.0041),
                                         (-0.0029, 0, 0.0017, 0.0041)):
                        box((w, h, 0.0002), (cx + dx, cy + dy, z + lift), sign, bevel=0)
                frame(0.0018, 0.0018, 0.0)
                # the nearer frame hides the farther one's corner: a patch of the cap's own colour between them
                box((0.0090, 0.0090, 0.0002), (-0.0018, -0.0018, z + 0.0002),
                    plastic('patch', lerp(linear(TONES['dark'][0]), linear(TONES['orange'][0]), glow), grain=0), bevel=0)
                frame(-0.0018, -0.0018, 0.0004)
            else:
                text(kind, 0, 0, z, 0.0128, sign)
        button(SMALL_CAP, p, glow, 'dark', draw, reach=0.009)
    return build


LARGE, LESS = (0.0914, 0.0457), (0.0777, 0.0389)   # the app's two sizes of bumper and trigger



def wide(name, size, letter):
    half_w, half_h = size[0] / 2, size[1] / 2
    which = 'wide-large-base' if size == LARGE else 'wide-small-base'
    return dict(name=name, size=size, base=(which, lambda: wide_base(half_w, half_h)),
                caps=[(name, wide_cap(name.upper(), half_w, half_h, letter), (0, 0), size)])


def small(name, kind):
    return dict(name=name, size=(SMALL, SMALL), base=('small-base', small_base), caps=[(name, small_cap(kind), (0, 0), (SMALL, SMALL))])


# ---------------------------------------------------------------- keycaps: an IBM keyboard's, any width in units

UNIT = 0.0400        # 1u: a letter's key, as wide as it is deep
KC_GAP = 0.0018      # between a cap and its socket's edge
KC_CORNER = 0.0045   # the cap's footprint's corners
KC_DRAFT = 0.0074    # how far its walls lean in, each side: the top is much smaller than the foot
KC_HEIGHT = 0.0120   # tall, as an IBM key is
KC_DISH = 0.0028     # the top's scoop: a cylinder across it, deepest along its middle
KC_TRAVEL = 0.0035   # a keyboard key's press: short, so its walls still show
KC_EDGE = 0.0010     # the top's rounded edge


def keycap_outline(units, tall=1.0):
    return outline(rbox(0, 0, units * UNIT / 2 - KC_GAP, tall * UNIT / 2 - KC_GAP, KC_CORNER), reach=units * UNIT)


def keycap_body(units, tall, tone, glow, count=1024):
    """
    A sculpted keycap, its top's lowest point at height nought: walls that lean in from its foot to a smaller top,
    a rounded edge, and a top scooped as a cylinder across it, so its left and right edges dip with the scoop.
    """
    foot = keycap_outline(units, tall)
    half_h = tall * UNIT / 2 - KC_GAP - KC_DRAFT          # the top's half depth, across which the scoop runs
    lift = KC_DISH                                        # the top's edges, front and back, are this high

    def dish(x, y):
        f = min(abs(y) / half_h, 1.0)
        return lift - KC_DISH * (1 - f * f)

    # (inset, height of a point, wear): walls, the rounded edge, then the top in rings towards its middle
    rings = [(0.0, lambda x, y: -KC_HEIGHT, 0.1), (KC_DRAFT * 0.5, lambda x, y: -KC_HEIGHT * 0.5 + dish(x, y) * 0.5 - KC_EDGE * 0.5, 0.3)]
    for k in range(0, 7):
        t = k / 6 * math.pi / 2
        inset = KC_DRAFT + KC_EDGE * (1 - math.cos(t))
        rings.append((inset, (lambda e: lambda x, y: dish(x, y) - KC_EDGE + e)(KC_EDGE * math.sin(t)), 1.0 if k < 6 else 0.4))
    reach_in = min(units * UNIT, tall * UNIT) / 2 - KC_GAP - KC_DRAFT - KC_EDGE
    for f in (0.12, 0.3, 0.5, 0.7, 0.88, 0.97):
        rings.append((KC_DRAFT + KC_EDGE + reach_in * f, dish, 0.1))
    mesh = bpy.data.meshes.new('keycap')
    bm = bmesh.new()
    worn = bm.verts.layers.float.new('wear')
    made = []
    for inset, height, wear in rings:
        row = []
        for i in range(count):
            a = i / count * math.tau
            r = foot(a, inset)
            x, y = math.cos(a) * r, math.sin(a) * r
            v = bm.verts.new((x, y, height(x, y)))
            v[worn] = wear
            row.append(v)
        made.append(row)
    for lower, upper in zip(made, made[1:]):
        for i in range(count):
            bm.faces.new((lower[i], lower[(i + 1) % count], upper[(i + 1) % count], upper[i]))
    middle = bm.verts.new((0, 0, dish(0, 0)))
    for i in range(count):
        bm.faces.new((made[-1][i], made[-1][(i + 1) % count], middle))
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces[:])
    bm.to_mesh(mesh)
    bm.free()
    factory._place(bpy.data.objects.new('keycap', mesh), cap_paint(tone, glow, 0.02), (0, 0, 0), sharp=35)


def keycap(units, tall=1.0, tone='pearl'):
    """A keycap that presses as the other caps do, lit orange once it's down."""
    def build(p, glow):
        with pose(0, 0, TOP + PROUD + 0.0030 - KC_TRAVEL * p):
            keycap_body(units, tall, tone, glow)
        if glow > 0.01:
            foot = keycap_outline(units, tall)
            halo(foot, hole_of(foot), 0, 0, glow, count=1024)
    return build


KEY_WIDTHS = (1, 1.25, 1.5, 1.75, 2, 2.25, 2.75, 6.25)   # a keyboard's widths, in units
KEY_TONES = ('pearl', 'slate')                          # letters, and modifiers


def keycap_scene(units, tone):
    """
    kc-pearl-1.25: Mouse & keys' keys. No socket: each stands on a plate that isn't drawn, at a panel's height, which
    catches its shadow and hides what of its walls is below it, as a keyboard's does.
    """
    size = (units * UNIT, UNIT)
    name = f'kc-{tone}-{units:g}'
    return dict(name=name, set='keycaps', size=size, base=(f'kc-{units:g}-plate', lambda: None), private=True, flat=TOP,
                caps=[(name, keycap(units, 1.0, tone), (0, 0), size)])


# ---------------------------------------------------------------- the toolbar: a row of square keys in one panel

CELL = 122 / PPM          # a key's cell: 0.085 of the screen's height, a whole number of pixels so the pieces join
END = 5 / PPM             # what the panel adds beyond the first and the last cell
TOOL_KEY = outline(rbox(0, 0, CELL / 2 - 0.0080, CELL / 2 - 0.0080, 0.0022))
TOOLS = ('adjust', 'keyboard', 'hide', 'show', 'add', 'remove', 'smaller', 'bigger', 'opacity', 'reset', 'done')


def toolbar_panel(keys):
    """
    One panel with [keys] holes in a row, each in its shallow well. Its paint is even (no smudges, no scratches),
    so a slice of it cut a cell wide repeats without a seam.
    """
    half_w, half_h, edge = keys * CELL / 2 + END, CELL / 2, 0.0013
    outer = outline(rbox(0, 0, half_w, half_h, 0.0016), reach=0.5)
    sweep(rim(outer, 2048), (0, 0, 0), PANEL_EVEN, 2048, bottom=False, top=False)
    cell = outline(rbox(0, 0, CELL / 2, half_h - edge, 0.0))
    for i in range(keys):
        with inside(group('cell', location=((i - (keys - 1) / 2) * CELL, 0, 0))):
            socket(hole_of(TOOL_KEY), [(cell, 0, TOP)], 1024, PANEL_EVEN, INSET_EVEN)
    for side in (-1, 1):     # the sliver of face between the last cell and the rounded edge
        x0, x1, y = side * keys * CELL / 2, side * (half_w - edge), half_h - edge
        shape([(x0, -y), (x1, -y), (x1, y), (x0, y)], (0, 0, TOP), PANEL_EVEN, normal=(0, 0, 1))


def stroke(points, width, z, material, closed=False):
    """A line of [width] through [points], its ends and corners round: what the signs are drawn with."""
    points = list(points)
    # No two pieces that overlap have their tops in one plane (that speckles in the render): each lies a hair higher
    # or lower than its neighbours, and the round joints above them all.
    for i, ((x0, y0), (x1, y1)) in enumerate(zip(points, points[1:] + points[:1] if closed else points[1:])):
        length = math.hypot(x1 - x0, y1 - y0)
        box((length, width, 0.0002), ((x0 + x1) / 2, (y0 + y1) / 2, z + (i % 3) * 0.00002), material, bevel=0,
            rotation=(0, 0, math.degrees(math.atan2(y1 - y0, x1 - x0))))
    for i, (x, y) in enumerate(points):
        dot(x, y, width / 2, z + 0.00006 + (i % 2) * 0.00002, material)


def dot(x, y, radius, z, material):
    sweep([(radius, 0, z - 0.0001), (radius, 0, z + 0.0001)], (x, y, 0), material, 32)


def arc(radius, start, end, steps=24):
    return [(math.cos(math.radians(start + (end - start) * i / steps)) * radius,
             math.sin(math.radians(start + (end - start) * i / steps)) * radius) for i in range(steps + 1)]


def tool_sign(kind, z, sign, face):
    """A tool's sign, in the manner of the View and Menu signs: [sign] is its paint, [face] the cap's own."""
    w = 0.0017
    if kind == 'adjust':            # three sliders
        for y, knob in ((0.0048, -0.0028), (0, 0.0032), (-0.0048, -0.0004)):
            stroke([(-0.0064, y), (0.0064, y)], 0.0011, z, sign)
            dot(knob, y, 0.0021, z + 0.0003, sign)
    elif kind == 'keyboard':
        stroke([(-0.0068, 0.0044), (0.0068, 0.0044), (0.0068, -0.0044), (-0.0068, -0.0044)], 0.0015, z, sign, closed=True)
        for x in (-0.0039, -0.0013, 0.0013, 0.0039):
            box((0.0014, 0.0014, 0.0002), (x, 0.0014, z), sign, bevel=0)
        stroke([(-0.0028, -0.0016), (0.0028, -0.0016)], 0.0013, z, sign)
    elif kind == 'show':            # an open eye: lids that meet at the corners, an iris and its pupil
        lid, corner = 0.0095, 0.0095 * math.sin(math.radians(40))
        for flip in (1, -1):
            stroke([(x, flip * (y - corner)) for x, y in arc(lid, 40, 140, 16)], 0.0017, z, sign)
        stroke(arc(0.0030, 0, 360, 32)[:-1], 0.0012, z + 0.00012, sign, closed=True)
        dot(0, 0, 0.0014, z + 0.00024, sign)
    elif kind == 'hide':            # a closed eye with its lashes: the eye of 'show', shut
        lid, corner, lift = 0.0095, 0.0095 * math.sin(math.radians(40)), 0.0012
        # The lower lid's curve, raised a little: the shut lids, a smile, its centre above it at (0, corner + lift).
        stroke([(x, corner + lift - y) for x, y in arc(lid, 40, 140, 16)], 0.0019, z, sign)
        for a in (60, 76, 90, 104, 120):
            t = math.radians(a)
            x, y = lid * math.cos(t), corner + lift - lid * math.sin(t)
            stroke([(x, y), (x + 0.0024 * math.cos(t), y - 0.0024 * math.sin(t))], 0.0013, z + 0.00012, sign)
    elif kind == 'add':             # a plus in a rounded square
        stroke([(-0.0062, 0.0062), (0.0062, 0.0062), (0.0062, -0.0062), (-0.0062, -0.0062)], 0.0015, z, sign, closed=True)
        stroke([(-0.0030, 0), (0.0030, 0)], 0.0015, z, sign)
        stroke([(0, -0.0030), (0, 0.0030)], 0.0015, z + 0.00012, sign)
    elif kind == 'remove':          # a bin
        stroke([(-0.0064, 0.0044), (0.0064, 0.0044)], 0.0015, z, sign)
        stroke([(-0.0018, 0.0064), (0.0018, 0.0064)], 0.0013, z, sign)
        stroke([(-0.0046, 0.0044), (-0.0038, -0.0066), (0.0038, -0.0066), (0.0046, 0.0044)], 0.0015, z, sign)
        for x in (-0.0014, 0.0014):
            stroke([(x, 0.0016), (x, -0.0038)], 0.0011, z, sign)
    elif kind in ('smaller', 'bigger'):
        stroke([(-0.0058, 0), (0.0058, 0)], 0.0022, z, sign)
        if kind == 'bigger':
            stroke([(0, -0.0058), (0, 0.0058)], 0.0022, z + 0.00012, sign)
    elif kind == 'opacity':         # a circle, half of it filled
        stroke(arc(0.0064, 0, 360, 40)[:-1], 0.0015, z, sign, closed=True)
        shape(arc(0.0064, 90, 270, 24), (0, 0, z + 0.00025), sign, normal=(0, 0, 1))
    elif kind == 'reset':           # an arrow round a circle
        stroke(arc(0.0058, 50, 340, 30), w, z, sign)
        tip = arc(0.0058, 50, 50, 1)[0]
        shape([(tip[0] + 0.0040, tip[1] + 0.0012), (tip[0] - 0.0012, tip[1] + 0.0034), (tip[0] - 0.0008, tip[1] - 0.0034)],
              (0, 0, z + 0.00025), sign, normal=(0, 0, 1))
    elif kind == 'done':            # a check
        stroke([(-0.0064, -0.0004), (-0.0020, -0.0050), (0.0068, 0.0050)], 0.0022, z, sign)


def tool_cap(kind):
    """A square dark key with a white sign; the sign is dark once the key is lit, as Factorio's pressed buttons are."""
    def build(p, glow):
        sign = plastic('sign', lerp(linear('#F4F4F4'), linear('#141314'), glow), rough=0.38, grain=0)
        face = plastic('patch', lerp(linear(TONES['dark'][0]), linear(TONES['orange'][0]), glow), grain=0)
        button(TOOL_KEY, p, glow, 'dark', lambda z: tool_sign(kind, z + 0.0002, sign, face), reach=0.009)
    return build


# ---------------------------------------------------------------- the FPS counter: a small screen in a panel

FPS_BOX = (990 / 2 / PPM, 270 / 2 / PPM)   # 132x36 dp on the phone; rendered twice over, the app scales it down
FPS_RIM, FPS_DEPTH = 0.0036, 0.0026


def fps_panel():
    """A bevelled panel round an inset screen: dark, a little glassy. The app writes the number and the graph on it."""
    w, h = FPS_BOX
    slope = FPS_DEPTH * 0.45
    outer = outline(rbox(0, 0, w / 2 - LINE_W, h / 2 - LINE_W, 0.0026), reach=0.3)
    well = outline(rbox(0, 0, w / 2 - FPS_RIM - slope, h / 2 - FPS_RIM - slope, 0.0012), reach=0.3)
    z = TOP - FPS_DEPTH
    rings = rim(outer, 2048) + [(well, -slope - 0.0006, TOP, 0.25), (well, -slope - 0.0002, TOP - 0.00005, 1.0),
                                (well, -slope * 0.95, TOP - FPS_DEPTH * 0.05, 1.0), (well, -slope * 0.86, TOP - FPS_DEPTH * 0.14, 0.5), (well, 0, z)]
    sweep(rings, (0, 0, 0), PANEL, 2048, bottom=False, top=False)
    sweep([(well, -0.0002, z)], (0, 0, 0), SCREEN, 2048, bottom=False)


# A scene is a control: its base (a sprite's name and how to build it; bases that are alike share one sprite), and its
# caps: (name, build(press, glow), the cap's centre from the base's, the size of the control it is[, where the cap
# itself is and how large, if that is not the same]).
# [flat] is a height: the caps' shadows then fall on a flat stand-in there, not on the base (the app slides the knob).
SCENES = [
    dict(name='stick', size=(0.128, 0.128), base=('stick-base', stick_base), flat=WELL,
         caps=[('stick-knob', stick_knob, (0, 0), (0.128, 0.128), (0, 0, 0.0524, 0.0524))]),
    # the right stick's knob, for when the pad is off: the same over the same base
    dict(name='stick-r', size=(0.128, 0.128), base=('stick-base', stick_base), flat=WELL,
         caps=[('stick-knob-r', lambda p, glow: stick_knob(p, glow, 'R'), (0, 0), (0.128, 0.128), (0, 0, 0.0524, 0.0524))]),
    # Mouse & keys' stick, for W, A, S and D: the same knob with no letter
    dict(name='stick-k', size=(0.128, 0.128), base=('stick-base', stick_base), flat=WELL,
         caps=[('stick-knob-k', lambda p, glow: stick_knob(p, glow, None), (0, 0), (0.128, 0.128), (0, 0, 0.0524, 0.0524))]),
    dict(name='trackpad', size=PAD, base=('trackpad', trackpad), caps=[]),
] + [dict(name=n, size=(FACE, FACE), base=('face-base', face_base), caps=[(n, face_cap(n), (0, 0), (FACE, FACE))]) for n in 'abxy'] + [
    dict(name='dpad', size=(DPAD, DPAD), base=('dpad-base', dpad_base),
         caps=[(f'dpad-{way}', dpad_cross(way), (0, 0), (DPAD, DPAD)) for way in WAYS]),
    wide('lb', LARGE, 0.0235), wide('rt', LARGE, 0.0235), wide('lt', LESS, 0.0190), wide('rb', LESS, 0.0190),
    small('view', 'view'), small('menu', 'menu'), small('l3', 'L3'), small('r3', 'R3'),
] + [keycap_scene(units, tone) for tone in KEY_TONES for units in KEY_WIDTHS] + [
    # the toolbar's keys: each over a one-key panel that only catches its shadow (the panel's own sprites are the
    # three pieces run_toolbar cuts)
    dict(name=f'tool-{kind}', set='tools', size=(CELL, CELL), base=('toolbar', lambda: toolbar_panel(1)), private=True,
         caps=[(f'tool-{kind}', tool_cap(kind), (0, 0), (CELL, CELL))]) for kind in TOOLS
]

# A press, frame by frame at 60 fps: (how far down, how lit). The thumb drives the cap down in four frames and it
# stops dead; only then does the light come up. Let go, it springs back past its rest and settles.
PRESS = [(0.0, 0.0), (0.10, 0.0), (0.32, 0.0), (0.60, 0.0), (0.86, 0.0), (1.0, 0.45), (1.0, 1.0)]
RELEASE = [(0.52, 0.2), (0.08, 0.0), (-0.13, 0.0), (-0.04, 0.0)]    # and then the first frame again: at rest
POSES = PRESS + RELEASE
if QUICK:
    POSES = [PRESS[0], PRESS[3], PRESS[6], RELEASE[2]]


# ---------------------------------------------------------------- rendering

def shoot(path, cx, cy, width, height, ppm=PPM):
    camera_data.ortho_scale = max(width, height)
    scene.render.resolution_x = round(width * ppm)
    scene.render.resolution_y = round(height * ppm)
    camera.location = (cx, cy, 3)
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def read(path):
    import numpy
    image = bpy.data.images.load(path, check_existing=False)
    w, h = image.size
    pixels = numpy.empty(w * h * 4, dtype=numpy.float32)
    image.pixels.foreach_get(pixels)
    bpy.data.images.remove(image)
    os.remove(path)
    pixels = pixels.reshape(h, w, 4)
    # The catcher leaves a percent or two of noise where there is nothing: fade that out, keep real shadow.
    pixels[..., 3] *= numpy.clip((pixels[..., 3] - 0.012) / 0.04, 0.0, 1.0)
    return pixels


def save(name, pixels):
    h, w = pixels.shape[:2]
    out = bpy.data.images.new(name, w, h, alpha=True)
    out.pixels.foreach_set(pixels.ravel())
    out.filepath_raw = os.path.join(OUT, f'{name}.png')
    out.file_format = 'PNG'
    out.save()
    bpy.data.images.remove(out)


def bounds(frames):
    """What the pictures draw, all together: left, right, bottom, top (Blender's rows run bottom up)."""
    import numpy
    mask = None
    for pixels in frames:
        alpha = pixels[..., 3] > 0.008
        mask = alpha if mask is None else (mask | alpha)
    rows, cols = numpy.where(mask)
    h, w = mask.shape
    return max(cols.min() - 2, 0), min(cols.max() + 3, w), max(rows.min() - 2, 0), min(rows.max() + 3, h)


def run(spec, done, places):
    import numpy
    width, height = spec['size']
    full_w, full_h = width + MARGIN_NEAR + MARGIN_FAR, height + MARGIN_NEAR + MARGIN_FAR
    shift = (MARGIN_FAR - MARGIN_NEAR) / 2
    rows = round(full_h * PPM)
    centre = ((MARGIN_NEAR + width / 2) * PPM, (MARGIN_NEAR + height / 2) * PPM)   # the control's, from the left and the top
    scratch = os.path.join(OUT, '.frame.png')
    base_name, build_base = spec['base']
    seed = zlib.crc32(base_name.encode())   # bases that share a sprite are built alike, part for part
    factory.rng.seed(seed)
    RND.seed(seed)
    base = group(base_name)
    with inside(base):
        build_base()
    if base_name not in done and not spec.get('private'):
        done.add(base_name)
        shoot(scratch, shift, -shift, full_w, full_h)
        pixels = read(scratch)
        far = round(0.022 * PPM)   # no shadow reaches further than this from the control
        pixels[:max(rows - round(centre[1] * 2 - MARGIN_NEAR * PPM) - far, 0)] = 0
        pixels[:, round(centre[0] * 2 - MARGIN_NEAR * PPM) + far:] = 0
        left, right, bottom, top = bounds([pixels])
        save(base_name, numpy.ascontiguousarray(pixels[bottom:top, left:right]))
        places[base_name] = {'file': f'{base_name}.png', 'w': int(right - left), 'h': int(top - bottom),
                             'cx': round(centre[0] - left, 1), 'cy': round(centre[1] - (rows - top), 1),
                             'controlW': round(width * PPM), 'controlH': round(height * PPM)}
    # Now the base only catches shadows: a cap's picture is the cap, its glow, and the shade it throws on the base.
    flat = spec.get('flat')
    for o in members(base):
        if flat is None:
            o.is_shadow_catcher = True
        else:
            o.hide_render = True
    ground.location.z = flat or 0.0
    for cap_name, build, (x, y), (cap_w, cap_h), *rest in spec['caps']:
        frames = []
        bx, by, bw, bh = rest[0] if rest else (x, y, cap_w, cap_h)   # the cap itself: its middle and size
        x0, x1 = round(centre[0] + (bx - bw / 2 - 0.009) * PPM), round(centre[0] + (bx + bw / 2 + 0.021) * PPM)
        y0, y1 = round(centre[1] - (by + bh / 2 + 0.009) * PPM), round(centre[1] - (by - bh / 2 - 0.021) * PPM)
        for p, glow in POSES:
            holder = group(cap_name)
            factory.rng.seed(seed)
            with inside(holder):
                build(p, glow)
            shoot(scratch, shift, -shift, full_w, full_h)
            pixels = read(scratch)
            # Far from the cap the catcher leaves only a trace of noise: keep what is near it, its shadow and its glow.
            near = numpy.zeros(pixels.shape[:2], dtype=bool)
            near[max(rows - y1, 0):rows - y0, max(x0, 0):x1] = True
            pixels[~near] = 0
            frames.append(pixels)
            clear(holder)
        left, right, bottom, top = bounds(frames)
        save(cap_name, numpy.ascontiguousarray(numpy.concatenate([f[bottom:top, left:right] for f in frames], axis=1)))
        mine = (centre[0] + x * PPM, centre[1] - y * PPM)
        down = len(PRESS) if not QUICK else 3
        places[cap_name] = {'file': f'{cap_name}.png', 'frameW': int(right - left), 'frameH': int(top - bottom), 'frames': len(POSES),
                            'fps': FPS, 'cx': round(mine[0] - left, 1), 'cy': round(mine[1] - (rows - top), 1),
                            'controlW': round(cap_w * PPM), 'controlH': round(cap_h * PPM),
                            'press': list(range(down)), 'release': list(range(down, len(POSES))) + [0], 'held': down - 1,
                            'base': base_name, 'offsetX': round(x * PPM, 1), 'offsetY': round(-y * PPM, 1)}
    ground.location.z = 0.0
    clear(base)


def run_toolbar(places):
    """
    The toolbar's panel in three pieces. Three keys' worth is rendered and cut 24 px into the first cell, into the
    second and into the third: so the left piece holds all of its end's shadow, the middle is one cell's pitch and
    repeats, and left + (keys - 1) middles + right is a panel for any number of keys.
    """
    import numpy
    seed = zlib.crc32(b'toolbar')
    factory.rng.seed(seed)
    RND.seed(seed)
    base = group('toolbar')
    with inside(base):
        toolbar_panel(3)
    near, far, cut, cell, end = 26, 84, 24, 122, 5
    width, height = near + end + 3 * cell + end + far, near + cell + far
    camera_data.ortho_scale = max(width, height) / PPM
    scene.render.resolution_x, scene.render.resolution_y = width, height
    camera.location = ((width / 2 - near - end - 1.5 * cell) / PPM, (cell / 2 + near - height / 2) / PPM, 3)   # cells on whole pixels
    scratch = os.path.join(OUT, '.frame.png')
    scene.render.filepath = scratch
    bpy.ops.render.render(write_still=True)
    pixels = read(scratch)
    pixels[height - (near - 3):] = 0      # nothing is drawn above the panel or left of it: its shadow falls down and right
    pixels[:, :near - 3] = 0
    left, right, bottom, top = bounds([pixels])
    left, top = near - 3, height - (near - 3)
    a = near + end + cut
    for name, x0, x1 in (('toolbar-left', left, a), ('toolbar-middle', a + cell, a + 2 * cell), ('toolbar-right', a + 2 * cell, right)):
        save(name, numpy.ascontiguousarray(pixels[bottom:top, x0:x1]))
        places[name] = {'file': f'{name}.png', 'w': int(x1 - x0), 'h': int(top - bottom)}
    clear(base)
    return {'left': 'toolbar-left', 'middle': 'toolbar-middle', 'right': 'toolbar-right',
            'join': 'left, then (keys - 1) middles, then right, side by side with their tops level',
            'keySize': cell, 'keyPitch': cell,
            # the first key's centre, from the left and the top of the joined row; each next key is keyPitch further right
            'firstKeyX': near + end + cell / 2 - int(left), 'keyY': near + cell / 2 - int(height - top),
            'panelLeft': near - int(left), 'panelTop': near - int(height - top), 'panelEnd': end}


def run_fps(places):
    """The FPS counter's panel, at twice the phone's size, and a sheet of glass for over what the app draws on it."""
    import numpy
    seed = zlib.crc32(b'fps')
    factory.rng.seed(seed)
    RND.seed(seed)
    base = group('fps')
    with inside(base):
        fps_panel()
    ppm = PPM * 2
    w, h = FPS_BOX
    full_w, full_h = w + MARGIN_NEAR + MARGIN_FAR, h + MARGIN_NEAR + MARGIN_FAR
    shift = (MARGIN_FAR - MARGIN_NEAR) / 2
    scratch = os.path.join(OUT, '.frame.png')
    shoot(scratch, shift, -shift, full_w, full_h, ppm)
    pixels = read(scratch)
    rows = pixels.shape[0]
    centre = ((MARGIN_NEAR + w / 2) * ppm, (MARGIN_NEAR + h / 2) * ppm)
    far = round(0.016 * ppm)
    pixels[:max(rows - round(centre[1] * 2 - MARGIN_NEAR * ppm) - far, 0)] = 0
    pixels[:, round(centre[0] * 2 - MARGIN_NEAR * ppm) + far:] = 0
    edge = round(MARGIN_NEAR * ppm) - 4       # nothing is drawn above the panel or left of it
    pixels[rows - edge:] = 0
    pixels[:, :edge] = 0
    left, right, bottom, top = bounds([pixels])
    left, top = edge, rows - edge
    part = numpy.ascontiguousarray(pixels[bottom:top, left:right])
    save('fps-panel', part)
    cx, cy = centre[0] - left, centre[1] - (rows - top)
    slope = FPS_DEPTH * 0.45
    half = ((w / 2 - FPS_RIM - slope) * ppm, (h / 2 - FPS_RIM - slope) * ppm)     # the screen's floor
    display = [round(cx - half[0]) + 1, round(cy - half[1]) + 1, round(half[0] * 2) - 2, round(half[1] * 2) - 2]
    dx, dy, dw, dh = display
    pad = round(dh * 0.11)
    column = round(dw * 0.27)
    places['fps-panel'] = {
        'file': 'fps-panel.png', 'w': int(right - left), 'h': int(top - bottom), 'cx': round(cx, 1), 'cy': round(cy, 1),
        'controlW': round(w * ppm), 'controlH': round(h * ppm), 'scale': 2,
        'display': {'x': dx, 'y': dy, 'w': dw, 'h': dh},     # the screen, in the sprite's pixels
        # suggested: the number right-aligned at numberRight, its baseline at numberBaseline; FPS under it; the graph's box
        'text': {'numberRight': dx + pad + column, 'numberBaseline': dy + round(dh * 0.60), 'unitBaseline': dy + dh - pad,
                 'numberSize': round(dh * 0.50), 'unitSize': round(dh * 0.28)},
        'graph': {'x': dx + pad * 2 + column, 'y': dy + pad, 'w': dw - pad * 3 - column, 'h': dh - pad * 2},
    }
    # The glass: no geometry, only light. The shade the screen's upper wall throws (it falls on the number too), and
    # a soft sheen slanting across the left of the screen. Drawn over the number and the graph.
    hh, ww = part.shape[:2]
    ys, xs = numpy.mgrid[0:hh, 0:ww].astype(numpy.float32)
    ys = hh - 1 - ys                                           # rows from the top
    u, v = (xs - dx) / dw, (ys - dy) / dh
    inside_ = (u >= 0) & (u <= 1) & (v >= 0) & (v <= 1)
    shade = 0.34 * numpy.exp(-(ys - dy) / (dh * 0.10)) + 0.10 * numpy.exp(-(xs - dx) / (dh * 0.06))
    sheen = 0.075 * numpy.exp(-(((u - 0.20) * dw + (v - 0.5) * dh * 0.55) / (dw * 0.075)) ** 2) + 0.035 * numpy.clip(1 - v * 1.6, 0, 1)
    shade, sheen = numpy.where(inside_, shade, 0), numpy.where(inside_, sheen, 0)
    alpha = sheen + shade * (1 - sheen)
    glass = numpy.zeros((hh, ww, 4), dtype=numpy.float32)
    glass[..., :3] = (sheen / numpy.maximum(alpha, 1e-6))[..., None]
    glass[..., 3] = alpha
    save('fps-glass', glass)
    places['fps-glass'] = {'file': 'fps-glass.png', 'w': int(ww), 'h': int(hh), 'over': 'fps-panel'}
    clear(base)


def wanted(name):
    return not ONLY or name in ONLY


chosen = [s for s in SCENES if wanted(s['name']) or (ONLY and s.get('set') in ONLY)]
info = os.path.join(OUT, 'sprites.json')
sheet = json.load(open(info)) if os.path.exists(info) else {'sprites': {}}
sheet.update({'pixelsPerMetre': PPM, 'screen': [3168, 1440]})
places = sheet['sprites']
done = set()
for spec in chosen:
    run(spec, done, places)
    json.dump(sheet, open(info, 'w'), indent=1)
    print('RENDERED', spec['name'], flush=True)
if wanted('toolbar') or wanted('tools'):
    sheet['toolbar'] = run_toolbar(places)
    json.dump(sheet, open(info, 'w'), indent=1)
    print('RENDERED toolbar', flush=True)
if wanted('fps'):
    run_fps(places)
    json.dump(sheet, open(info, 'w'), indent=1)
    print('RENDERED fps', flush=True)
