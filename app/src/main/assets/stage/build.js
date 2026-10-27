// The Importing screen: Pocket Foundry's own factory, rendered the way Factorio renders its sprites (3D
// models seen from above with a tilt; an oblique projection keeps the ground grid square), lit by a
// low sun from the north-west so shadows fall to the south-east. It starts as the Welcome screen's
// blueprint, ghosts only; each piece rises out of the paper when the import passes its percent,
// and works from then on. Everything moves from the blueprint simulation (tools/stage/blueprint.py).
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { RoomEnvironment } from 'three/addons/environments/RoomEnvironment.js';
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js';
import { EffectComposer } from 'three/addons/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/addons/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/addons/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/addons/postprocessing/OutputPass.js';

const S = await (await fetch('build-sim.json')).json();
const TILE = 16;                      // blueprint px per tile
const TILT = 0.8;                     // screen rise per unit of height
const VIEW = { x: 27.5, y: -12.5, w: 55, h: 25 };   // the blueprint, in tiles

const wx = px => px / TILE;
const wy = py => -py / TILE;

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(renderer), 0.04).texture;

const camera = new THREE.OrthographicCamera(0, 55, 0, -25, 0.1, 200);
camera.position.set(0, 0, 100);
camera.lookAt(0, 0, 0);

// Fills the screen like CSS cover: the blueprint's middle stays in view whatever the shape.
function fit(width, height) {
  const aspect = width / height;
  const w = aspect > VIEW.w / VIEW.h ? VIEW.w : VIEW.h * aspect, h = w / aspect;
  Object.assign(camera, { left: VIEW.x - w / 2, right: VIEW.x + w / 2, top: VIEW.y + h / 2, bottom: VIEW.y - h / 2 });
  camera.updateProjectionMatrix();
  camera.projectionMatrix.multiply(new THREE.Matrix4().set(1, 0, 0, 0, 0, 1, TILT, TILT * 100, 0, 0, 1, 0, 0, 0, 0, 1));
  camera.projectionMatrixInverse.copy(camera.projectionMatrix).invert();
  renderer.setSize(width, height, false);
  composer.setPixelRatio(renderer.getPixelRatio());
  composer.setSize(width, height);
}

const sun = new THREE.DirectionalLight(0xffeedd, 3.4);
sun.position.set(27.5 - 18, -12.5 + 22, 40);
sun.target.position.set(27.5, -12.5, 0);
sun.castShadow = true;
sun.shadow.mapSize.set(2048, 2048);
Object.assign(sun.shadow.camera, { left: -45, right: 45, top: 45, bottom: -45, near: 1, far: 120 });
sun.shadow.bias = -0.0004;
sun.shadow.radius = 3;
scene.add(sun, sun.target);
scene.add(new THREE.HemisphereLight(0xcfe3ff, 0x0b1a2b, 0.3));

// Grime: speckled noise and streaks the metal colours are multiplied by, so nothing looks new.
const grime = (() => {
  const c = document.createElement('canvas');
  c.width = c.height = 256;
  const g = c.getContext('2d');
  g.fillStyle = '#e6e6e6'; g.fillRect(0, 0, 256, 256);
  let seed = 7;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  for (let i = 0; i < 9000; i++) {
    const v = 150 + rnd() * 105 | 0;
    g.fillStyle = `rgba(${v},${v - 6},${v - 12},${0.25 + rnd() * 0.4})`;
    g.fillRect(rnd() * 256, rnd() * 256, 1 + rnd() * 2, 1 + rnd() * 2);
  }
  for (let i = 0; i < 60; i++) {
    g.fillStyle = `rgba(70,55,40,${0.05 + rnd() * 0.08})`;
    g.fillRect(rnd() * 256, rnd() * 256, 2 + rnd() * 4, 20 + rnd() * 60);
  }
  const t = new THREE.CanvasTexture(c);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
})();

// The floor: blueprint paper, unlit, with the shadows laid over it.
{
  const c = document.createElement('canvas');
  c.width = 1760; c.height = 800;
  const g = c.getContext('2d');
  const grad = g.createRadialGradient(880, 400, 0, 880, 400, 1100);
  grad.addColorStop(0, '#143352'); grad.addColorStop(1, '#091726');
  g.fillStyle = grad; g.fillRect(0, 0, 1760, 800);
  for (const [step, alpha, width] of [[32, 0.09, 1], [128, 0.18, 2]]) {
    g.strokeStyle = `rgba(120,170,230,${alpha})`; g.lineWidth = width;
    g.beginPath();
    for (let x = 0; x <= 1760; x += step) { g.moveTo(x, 0); g.lineTo(x, 800); }
    for (let y = 0; y <= 800; y += step) { g.moveTo(0, y); g.lineTo(1760, y); }
    g.stroke();
  }
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  tex.anisotropy = 8;
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(55, 25), new THREE.MeshBasicMaterial({ map: tex, toneMapped: false }));
  floor.position.set(27.5, -12.5, 0);
  scene.add(floor);
  const shade = new THREE.Mesh(new THREE.PlaneGeometry(80, 50), new THREE.ShadowMaterial({ opacity: 0.5, color: 0x02060c }));
  shade.position.set(27.5, -12.5, 0.002);
  shade.receiveShadow = true;
  scene.add(shade);
}

const M = {
  steel: new THREE.MeshStandardMaterial({ color: '#8692a0', metalness: 0.6, roughness: 0.5, map: grime, envMapIntensity: 0.6 }),
  dark: new THREE.MeshStandardMaterial({ color: '#3a414b', metalness: 0.5, roughness: 0.65, map: grime, envMapIntensity: 0.5 }),
  darker: new THREE.MeshStandardMaterial({ color: '#1d2228', metalness: 0.3, roughness: 0.8 }),
  amber: new THREE.MeshStandardMaterial({ color: '#e39c40', metalness: 0.25, roughness: 0.5, map: grime }),
  brass: new THREE.MeshStandardMaterial({ color: '#b78c4c', metalness: 0.75, roughness: 0.32 }),
  rubber: new THREE.MeshStandardMaterial({ color: '#25272b', metalness: 0.05, roughness: 0.92 }),
  slat: new THREE.MeshStandardMaterial({ color: '#3d4046', metalness: 0.1, roughness: 0.8 }),
  copper: new THREE.MeshStandardMaterial({ color: '#c9783a', metalness: 0.8, roughness: 0.3 }),
  wood: new THREE.MeshStandardMaterial({ color: '#7a5a3c', metalness: 0.0, roughness: 0.85 }),
  white: new THREE.MeshStandardMaterial({ color: '#e8eef5', metalness: 0.1, roughness: 0.4 }),
  plate: new THREE.MeshStandardMaterial({ color: '#aeb9c5', metalness: 0.7, roughness: 0.32, map: grime }),
  gearItem: new THREE.MeshStandardMaterial({ color: '#98a4b1', metalness: 0.7, roughness: 0.35 }),
  flask: new THREE.MeshStandardMaterial({ color: '#f1a240', emissive: '#f1a240', emissiveIntensity: 0.35, metalness: 0.1, roughness: 0.15 }),
  glass: new THREE.MeshStandardMaterial({ color: '#2f6fae', metalness: 0.2, roughness: 0.08, transparent: true, opacity: 0.5, envMapIntensity: 0.35, depthWrite: false }),
  rotor: new THREE.MeshStandardMaterial({ color: '#cfe3ff', transparent: true, opacity: 0.35 }),
};

function mesh(geometry, material, x = 0, y = 0, z = 0, parent = scene) {
  const m = new THREE.Mesh(geometry, material);
  m.position.set(x, y, z);
  m.castShadow = m.receiveShadow = true;
  parent.add(m);
  return m;
}
const upright = g => g.rotateX(Math.PI / 2);          // three's cylinders stand on Y; ours on Z
function rod(a, b, r, material, parent) {
  const d = new THREE.Vector3().subVectors(b, a);
  const m = mesh(new THREE.CylinderGeometry(r, r, d.length(), 10), material, 0, 0, 0, parent);
  m.position.copy(a).addScaledVector(d, 0.5);
  m.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), d.normalize());
  return m;
}
function gearShape(outer, inner, teeth, hole) {
  const s = new THREE.Shape();
  for (let k = 0; k <= teeth * 4; k++) {
    const a = 2 * Math.PI * k / (teeth * 4);
    const r = (k % 4 === 1 || k % 4 === 2) ? outer : inner;
    k === 0 ? s.moveTo(r * Math.cos(a), r * Math.sin(a)) : s.lineTo(r * Math.cos(a), r * Math.sin(a));
  }
  if (hole) { const h = new THREE.Path(); h.absarc(0, 0, hole, 0, Math.PI * 2, true); s.holes.push(h); }
  return s;
}

const spinners = [], pulsers = [], entities = [];
// Returns the piece's record; `at` (seconds) is when it got built, Infinity while it's a plan.
function planned(group, percent) {
  const fill = new THREE.MeshBasicMaterial({ color: '#5f9de8', transparent: true, opacity: 0.2, depthWrite: false });
  const line = new THREE.LineBasicMaterial({ color: '#cfe3ff', transparent: true, opacity: 0.6 });
  const ghost = new THREE.Group();
  group.updateMatrixWorld(true);
  group.traverse(o => {
    if (!o.isMesh || o.isInstancedMesh || o.userData.noGhost) return;
    const edges = new THREE.EdgesGeometry(o.geometry, 40);
    // Round parts have no sharp edges: no outline to draw.
    for (const copy of edges.attributes.position.count ? [new THREE.Mesh(o.geometry, fill), new THREE.LineSegments(edges, line)] : [new THREE.Mesh(o.geometry, fill)]) {
      o.matrixWorld.decompose(copy.position, copy.quaternion, copy.scale);
      ghost.add(copy);
    }
  });
  scene.add(ghost);
  const entity = { group, ghost, fill, line, percent, at: Infinity };
  entities.push(entity);
  return entity;
}

// Assembling machine, 3x3: a steel housing with an open hatch where a gear train turns.
function assembler(cx, cy, k) {
  const g = new THREE.Group(); g.position.set(cx, cy, 0); scene.add(g);
  mesh(new RoundedBoxGeometry(2.92, 2.92, 0.3, 2, 0.06), M.dark, 0, 0, 0.15, g);
  mesh(new RoundedBoxGeometry(2.6, 2.6, 1.05, 3, 0.14), M.steel, 0, 0, 0.78, g);
  mesh(new RoundedBoxGeometry(1.78, 1.78, 0.2, 2, 0.05), M.darker, 0, 0, 1.25, g);
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) mesh(upright(new THREE.CylinderGeometry(0.09, 0.09, 0.08, 10)), M.brass, sx * 1.08, sy * 1.08, 1.33, g);
  for (let i = -1; i <= 1; i++) mesh(new THREE.BoxGeometry(0.5, 0.06, 0.1), M.darker, i * 0.7, -1.31, 0.62, g);
  mesh(new THREE.BoxGeometry(2.0, 0.05, 0.1), M.amber, 0, -1.31, 0.95, g);
  for (const sx of [-1, 1]) mesh(new THREE.CylinderGeometry(0.15, 0.15, 0.32, 14).rotateZ(Math.PI / 2), M.dark, sx * 1.4, 0.3, 0.55, g);
  const big = new THREE.Group(); big.position.set(-0.22, 0.12, 1.36); g.add(big);
  mesh(new THREE.ExtrudeGeometry(gearShape(0.62, 0.5, 12, 0.12), { depth: 0.1, bevelEnabled: false }), M.brass, 0, 0, 0, big);
  mesh(new THREE.BoxGeometry(0.9, 0.12, 0.08), M.dark, 0.2, 0, 0.12, big);
  const small = new THREE.Group(); small.position.set(0.55, -0.42, 1.36); g.add(small);
  mesh(new THREE.ExtrudeGeometry(gearShape(0.32, 0.22, 8, 0.07), { depth: 0.1, bevelEnabled: false }), M.steel, 0, 0, 0, small);
  spinners.push({ obj: big, period: 8, phase: k * 0.7 }, { obj: small, period: -4, phase: k * 0.7 });
  chimneyAndLamp(g, k);
  planned(g, S.build.assemblers[k]);
}
function chimneyAndLamp(g, k) {
  mesh(upright(new THREE.CylinderGeometry(0.22, 0.26, 1.1, 14)), M.dark, -0.95, 0.95, 1.6, g);
  mesh(upright(new THREE.CylinderGeometry(0.25, 0.25, 0.08, 14)), M.amber, -0.95, 0.95, 2.13, g);
  mesh(upright(new THREE.CylinderGeometry(0.15, 0.15, 0.05, 12)), M.darker, -0.95, 0.95, 2.18, g);
  const lamp = mesh(new THREE.SphereGeometry(0.09, 12, 8), new THREE.MeshStandardMaterial({ color: '#f1a240', emissive: '#f1a240', emissiveIntensity: 1.5 }), 1.05, -1.05, 1.34, g);
  lamp.castShadow = false;
  pulsers.push({ material: lamp.material, period: 3.2, phase: k * 0.9, low: 0.3, high: 2.2 });
}

// Lab, 3x3: an octagonal plinth under a ribbed glass dome with a glowing core.
function lab(cx, cy, k) {
  const g = new THREE.Group(); g.position.set(cx, cy, 0); scene.add(g);
  mesh(upright(new THREE.CylinderGeometry(1.42, 1.5, 0.42, 8)).rotateZ(Math.PI / 8), M.dark, 0, 0, 0.21, g);
  mesh(upright(new THREE.CylinderGeometry(1.2, 1.3, 0.12, 8)).rotateZ(Math.PI / 8), M.steel, 0, 0, 0.48, g);
  const coreMaterial = new THREE.MeshStandardMaterial({ color: '#9fe0ff', emissive: '#7dcaed', emissiveIntensity: 2 });
  const core = mesh(new THREE.SphereGeometry(0.42, 20, 14), coreMaterial, 0, 0, 0.95, g);
  core.castShadow = false;
  pulsers.push({ material: coreMaterial, period: 3.2, phase: k * 0.8, low: 1.2, high: 4.5 });
  const dome = mesh(new THREE.SphereGeometry(1.08, 28, 14, 0, Math.PI * 2, 0, Math.PI / 2).rotateX(Math.PI / 2), M.glass, 0, 0, 0.54, g);
  dome.castShadow = false;
  for (let i = 0; i < 4; i++) {
    const rib = mesh(new THREE.TorusGeometry(1.09, 0.035, 6, 28, Math.PI), M.steel, 0, 0, 0.54, g);
    rib.rotation.set(Math.PI / 2, 0, i * Math.PI / 4, 'ZXY');     // a half ring stood up, then turned
  }
  mesh(new THREE.TorusGeometry(1.1, 0.06, 8, 32), M.brass, 0, 0, 0.56, g);
  planned(g, S.build.labs[k]);
}

// Belt: a rubber bed between two rails, slats sliding along it.
const beltRuns = [];
function belt(name, b) {
  const y = wy(b.y), g = new THREE.Group(); g.position.set(27.5, y, 0); scene.add(g);
  mesh(new THREE.BoxGeometry(60, 0.86, 0.12), M.rubber, 0, 0, 0.06, g).castShadow = false;
  for (const s of [-1, 1]) {
    mesh(new THREE.BoxGeometry(60, 0.1, 0.22), M.dark, 0, s * 0.47, 0.11, g);
    mesh(new THREE.BoxGeometry(60, 0.04, 0.02), M.amber, 0, s * 0.47, 0.23, g);
  }
  const slats = new THREE.InstancedMesh(new THREE.BoxGeometry(0.07, 0.8, 0.03), M.slat, 240);
  slats.receiveShadow = true;
  g.add(slats);
  const itemGeometry = {
    plate: new THREE.BoxGeometry(0.46, 0.3, 0.05).translate(0, 0, 0.025),
    gear: new THREE.ExtrudeGeometry(gearShape(0.25, 0.19, 8, 0.06), { depth: 0.07, bevelEnabled: false }),
    science: flaskGeometry(),
  }[b.item];
  const itemMaterial = { plate: M.plate, gear: M.gearItem, science: M.flask }[b.item];
  const items = new THREE.InstancedMesh(itemGeometry, itemMaterial, S.slots);
  items.castShadow = items.receiveShadow = true;
  // Bounds are measured once, at the first draw, when the belt may still be empty: never cull.
  items.frustumCulled = slats.frustumCulled = false;
  scene.add(items);
  beltRuns.push({ b, y, slats, items, entity: planned(g, S.build.belts[name]) });
}
function flaskGeometry() {
  return mergeGeometries([
    upright(new THREE.CylinderGeometry(0.13, 0.15, 0.2, 12)).translate(0, 0, 0.1),
    upright(new THREE.CylinderGeometry(0.05, 0.1, 0.1, 10)).translate(0, 0, 0.25),
    upright(new THREE.CylinderGeometry(0.06, 0.06, 0.05, 10)).translate(0, 0, 0.32),
  ]);
}
const itemMesh = {
  plate: () => new THREE.Mesh(new THREE.BoxGeometry(0.42, 0.28, 0.05), M.plate),
  gear: () => new THREE.Mesh(new THREE.ExtrudeGeometry(gearShape(0.24, 0.18, 8, 0.06), { depth: 0.07, bevelEnabled: false }), M.gearItem),
  science: () => new THREE.Mesh(flaskGeometry(), M.flask),
};

// Inserter: a turntable on a round base, an elbowed arm and a two-finger hand.
const arms = [];
function inserter(s) {
  const cx = wx(s.cx), cy = wy(s.cy);
  const g = new THREE.Group(); g.position.set(cx, cy, 0); scene.add(g);
  mesh(upright(new THREE.CylinderGeometry(0.3, 0.36, 0.12, 18)), M.dark, 0, 0, 0.06, g);
  mesh(upright(new THREE.CylinderGeometry(0.2, 0.24, 0.1, 16)), M.amber, 0, 0, 0.17, g);
  const turn = new THREE.Group(); turn.position.set(0, 0, 0.22); g.add(turn);
  const out = -s.pick;                                   // towards the pickup side at rest
  const shoulder = new THREE.Vector3(0, 0, 0.08), elbow = new THREE.Vector3(0, out * 0.42, 0.62), wrist = new THREE.Vector3(0, out * 0.98, 0.3);
  mesh(new THREE.SphereGeometry(0.1, 12, 8), M.dark, 0, 0, 0.08, turn);
  rod(shoulder, elbow, 0.065, M.amber, turn);
  rod(elbow, wrist, 0.05, M.amber, turn);
  mesh(new THREE.SphereGeometry(0.075, 10, 8), M.dark, elbow.x, elbow.y, elbow.z, turn);
  for (const f of [-1, 1]) mesh(new THREE.BoxGeometry(0.05, 0.05, 0.18), M.dark, f * 0.09, wrist.y, wrist.z - 0.08, turn);
  const held = itemMesh[s.item]();
  held.castShadow = true;
  held.userData.noGhost = true;
  held.position.set(0, wrist.y, wrist.z - 0.22);
  turn.add(held);
  arms.push({ s, turn, held, entity: planned(g, S.build.inserters[arms.length]) });
}

// Small electric pole with copper wires sagging to its neighbour.
function pole(px, py, percent) {
  const x = wx(px), y = wy(py);
  const g = new THREE.Group(); g.position.set(x, y, 0); scene.add(g);
  mesh(upright(new THREE.CylinderGeometry(0.06, 0.08, 1.7, 8)), M.wood, 0, 0, 0.85, g);
  mesh(new THREE.BoxGeometry(0.56, 0.07, 0.07), M.wood, 0, 0, 1.62, g);
  for (const s of [-1, 1]) mesh(upright(new THREE.CylinderGeometry(0.035, 0.035, 0.12, 8)), M.white, s * 0.24, 0, 1.72, g);
  mesh(upright(new THREE.CylinderGeometry(0.16, 0.18, 0.06, 10)), M.dark, 0, 0, 0.03, g);
  planned(g, percent);
  return new THREE.Vector3(x, y, 1.76);
}
function wire(a, b, percent) {
  const pts = [];
  for (let i = 0; i <= 24; i++) {
    const t = i / 24;
    pts.push(new THREE.Vector3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t - 0.45 * 4 * t * (1 - t)));
  }
  const g = new THREE.Group(); scene.add(g);
  const m = mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(pts), 24, 0.022, 5), M.copper, 0, 0, 0, g);
  m.receiveShadow = false;
  planned(g, percent);
}

// Construction drone: a body, four arms and spinning rotors, flying three tiles up.
const drones = [];
function drone(points, period, offset) {
  const g = new THREE.Group(); scene.add(g);
  mesh(new RoundedBoxGeometry(0.5, 0.34, 0.16, 2, 0.05), M.steel, 0, 0, 0, g);
  const light = mesh(new THREE.SphereGeometry(0.05, 8, 6), new THREE.MeshStandardMaterial({ color: '#f1a240', emissive: '#f1a240', emissiveIntensity: 2 }), 0.26, 0, 0.02, g);
  light.castShadow = false;
  const rotors = [];
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) {
    rod(new THREE.Vector3(0, 0, 0), new THREE.Vector3(sx * 0.36, sy * 0.3, 0.04), 0.025, M.dark, g);
    const r = mesh(upright(new THREE.CylinderGeometry(0.17, 0.17, 0.01, 16)), M.rotor, sx * 0.36, sy * 0.3, 0.08, g);
    r.castShadow = false;
    rotors.push(r);
  }
  const curve = new THREE.CubicBezierCurve3(...points.map(([x, y]) => new THREE.Vector3(wx(x), wy(y), 3)));
  drones.push({ g, curve, period, offset });
}

for (const [name, b] of Object.entries(S.belts)) belt(name, b);
S.assemblers.forEach((x, k) => assembler(wx(x), wy(S.assemblerY), k));
S.labs.forEach((x, k) => lab(wx(x), wy(S.labY), k));
S.inserters.forEach(inserter);
const tops = S.poles.map(([x, y], i) => pole(x, y, S.build.poles[i]));
for (let i = 1; i < tops.length; i++) if (tops[i].y === tops[i - 1].y) wire(tops[i - 1], tops[i], Math.max(S.build.poles[i - 1], S.build.poles[i]));
drone([[-40, 210], [220, 30], [520, 390], [920, 170]], 16, 0.12);
drone([[920, 340], [640, 190], [300, 430], [-40, 70]], 32, 0.55);

// Lights glow, as the game draws them on top of its sprites.
const composer = new EffectComposer(renderer);
composer.addPass(new RenderPass(scene, camera));
composer.addPass(new UnrealBloomPass(new THREE.Vector2(256, 256), 0.55, 0.35, 0.82));
composer.addPass(new OutputPass());

// The arm's swing, as in the blueprint: rest, ease over, rest, ease back.
function bezier(x1, y1, x2, y2) {
  return x => {
    let lo = 0, hi = 1;
    for (let i = 0; i < 24; i++) {
      const t = (lo + hi) / 2, bx = 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t * t * x2 + t ** 3;
      bx < x ? lo = t : hi = t;
    }
    const t = (lo + hi) / 2;
    return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t * t * y2 + t ** 3;
  };
}
const ease = bezier(0.45, 0, 0.55, 1);
function swing(c) {
  if (c < 0.12) return 0;
  if (c < 0.45) return 180 * ease((c - 0.12) / 0.33);
  if (c < 0.62) return 180;
  if (c < 0.95) return 180 * (1 - ease((c - 0.62) / 0.33));
  return 0;
}

const m4 = new THREE.Matrix4(), hidden = new THREE.Matrix4().makeScale(0, 0, 0);
function itemThere(b, k, n, t) {
  const v = b.visible[k], actor = b.actor[k];
  const want = b.full ? '0' : '1';                       // the state the inserter's action left
  if (v[n] !== want || actor === null) return v[n] === '1';
  let back = 0;
  while (back < S.period && v[(n - back - 1 + S.period) % S.period] === want) back++;
  const done = t - back * S.tick < arms[actor].entity.at + 0.6;   // the action came before its inserter worked
  return b.full ? done : !done;
}
const easeOut = x => 1 - (1 - x) ** 3;
function render(t) {
  const tick = t / S.tick, n = Math.floor(tick + 1e-6) % S.period;
  const length = S.x1 - S.x0;
  for (const e of entities) {
    const p = Math.min(1, Math.max(0, (t - e.at) / 0.6));
    e.group.visible = p > 0;
    e.group.scale.z = Math.max(0.001, easeOut(p));      // rises out of the blueprint
    e.ghost.visible = p < 1;
    e.fill.opacity = 0.2 * (1 - p);
    e.line.opacity = 0.6 * (1 - p);
  }
  for (const run of beltRuns) {
    const b = run.b;
    for (let k = 0; k < S.slots; k++) {
      const u = (k * S.gap + S.speed * tick) % length;
      const x = b.direction > 0 ? S.x0 + u : S.x1 - u;
      if (t >= run.entity.at + 0.6 && itemThere(b, k, n, t)) {
        m4.makeRotationZ(b.item === 'plate' ? 0 : (k * 0.7) % 6.28).setPosition(wx(x), run.y, 0.12);
        run.items.setMatrixAt(k, m4);
      } else run.items.setMatrixAt(k, hidden);
    }
    run.items.instanceMatrix.needsUpdate = true;
    const travel = (S.speed * tick / TILE) % 0.25;
    for (let i = 0; i < 240; i++) {
      const x = -30 + i * 0.25 + (b.direction > 0 ? travel : -travel);
      run.slats.setMatrixAt(i, m4.makeTranslation(x, 0, 0.135));
    }
    run.slats.instanceMatrix.needsUpdate = true;
  }
  for (const a of arms) {
    const c = (((tick - a.s.phase) % S.cycle) + S.cycle) % S.cycle / S.cycle;
    a.turn.rotation.z = -THREE.MathUtils.degToRad(swing(c));
    a.held.visible = a.s.holding[n] === '1' && t >= a.entity.at + 0.6;
  }
  for (const s of spinners) s.obj.rotation.z = 2 * Math.PI * (t + s.phase) / s.period;
  for (const p of pulsers) p.material.emissiveIntensity = p.low + (p.high - p.low) * (0.5 - 0.5 * Math.cos(2 * Math.PI * (t / p.period + p.phase)));
  for (const d of drones) {
    const f = ((t / d.period + d.offset) % 1 + 1) % 1;
    const p = d.curve.getPointAt(f), q = d.curve.getTangentAt(f);
    d.g.position.copy(p);
    d.g.position.z += 0.08 * Math.sin(2 * Math.PI * t / 1.6);
    d.g.rotation.z = Math.atan2(q.y, q.x);
    d.g.children.forEach((c, i) => { if (c.material === M.rotor) c.rotation.z = t * 40 + i; });
  }
  composer.render();
}

/** Puts the scene in [container]; the caller drives it with progress(percent). */
export function start(container) {
  container.appendChild(renderer.domElement);
  const resize = () => fit(container.clientWidth || innerWidth, container.clientHeight || innerHeight);
  new ResizeObserver(resize).observe(container);
  resize();
  const t0 = performance.now();
  let running = false;
  const frame = () => {
    if (!running) return;
    render((performance.now() - t0) / 1000);
    requestAnimationFrame(frame);
  };
  let last = 0;
  return {
    progress(percent) {
      const now = (performance.now() - t0) / 1000;
      if (percent < last) for (const e of entities) e.at = Infinity;   // a new import: back to the plan
      last = percent;
      for (const e of entities) if (e.at === Infinity && e.percent <= percent) e.at = now;
    },
    resume() { if (!running) { running = true; requestAnimationFrame(frame); } },
    pause() { running = false; },
  };
}
