// The Home, Paused and Settings screens: a small working factory drawn from the player's own
// Factorio sprites (served from their import at /game/, never shipped). Belts and inserters play
// tools/stage/factory_sim.py's loop: an inserter takes one real item off its belt.

const FPS = 30;

/** Puts the factory in [container]; rejects when the game's sprites can't be loaded. */
export async function start(container) {
  const S = await (await fetch('factory-sim.json')).json();
  const { W, H, T, frames: FRAMES, cycle: CYCLE } = S;
  const sheets = {
    grass: 'terrain/grass-1.png',
    amBase: 'entity/assembling-machine-2/assembling-machine-2-base.png',
    amAnim: 'entity/assembling-machine-2/assembling-machine-2-anim.png',
    amShadow: 'entity/assembling-machine-2/assembling-machine-2-shadow.png',
    lab: 'entity/lab/lab.png',
    labPatch: 'entity/lab/lab-integration.png',
    labShadow: 'entity/lab/lab-shadow.png',
    belt: 'entity/transport-belt/transport-belt.png',
    pole: 'entity/small-electric-pole/small-electric-pole.png',
    platform: 'entity/inserter/inserter-platform.png',
    arm: 'entity/inserter/inserter-hand-base.png',
    handOpen: 'entity/inserter/inserter-hand-open.png',
    handClosed: 'entity/inserter/inserter-hand-closed.png',
    robot: 'entity/construction-robot/construction-robot.png',
    robotShadow: 'entity/construction-robot/construction-robot-shadow.png',
    plate: 'icons/iron-plate.png',
    gear: 'icons/iron-gear-wheel.png',
    science: 'icons/automation-science-pack.png',
  };
  const load = path => new Promise((ok, fail) => {
    const img = new Image();
    img.onload = () => ok(img);
    img.onerror = () => fail(new Error(`missing ${path}`));
    img.src = `/game/${path}`;
  });
  const img = Object.fromEntries(await Promise.all(Object.entries(sheets).map(async ([k, p]) => [k, await load(p)])));

  const canvas = document.createElement('canvas');
  canvas.style.cssText = 'width:100%;height:100%;object-fit:cover;display:block';
  container.appendChild(canvas);
  const scale = Math.min(1.5, Math.max(1, (container.clientHeight || innerHeight) * devicePixelRatio / H));
  canvas.width = Math.round(W * scale);
  canvas.height = Math.round(H * scale);
  const ctx = canvas.getContext('2d');
  ctx.imageSmoothingQuality = 'high';

  // Static layers: the grass, and the copper wires with their shadows.
  const layer = draw => {
    const c = document.createElement('canvas');
    c.width = W; c.height = H;
    draw(c.getContext('2d'));
    return c;
  };
  const ground = layer(g => {
    let i = 0;
    for (let gy = 0; gy < H; gy += 256) for (let gx = 0; gx < W; gx += 256) {
      g.drawImage(img.grass, S.ground[i++] * 256, 320, 256, 256, gx, gy, 256, 256);
    }
  });
  const wires = layer(g => {
    const top = S.poles.filter(p => p[1] === 3.5);
    const rows = [[[-1.5, 3.5], ...top, [29.5, 3.5]], S.poles.filter(p => p[1] > 10)];
    g.lineWidth = 3;
    for (const row of rows) {
      const tops = row.map(([x, y]) => [x * T, y * T - 114]);
      for (let k = 1; k < tops.length; k++) {
        const [ax, ay] = tops[k - 1], [bx, by] = tops[k];
        for (const [colour, ox, oy] of [['rgba(30,24,16,0.31)', 44, 150], ['rgb(150,92,52)', 0, 0]]) {
          g.strokeStyle = colour;
          g.beginPath();
          for (let s = 0; s <= 40; s++) {
            const t = s / 40;
            const x = ax + 14 + (bx - ax - 28) * t + ox, y = ay + (by - ay) * t + 120 * t * (1 - t) + oy;
            s ? g.lineTo(x, y) : g.moveTo(x, y);
          }
          g.stroke();
        }
      }
    }
  });

  // Sprites: a frame of a sheet, centred on tile (cx, cy) plus Factorio's util.by_pixel shift.
  function put(sheet, sx, sy, sw, sh, cx, cy, shift = [0, 0], dw = sw, dh = sh) {
    ctx.drawImage(sheet, sx, sy, sw, sh, Math.round(cx * T + shift[0] * 2 - dw / 2), Math.round(cy * T + shift[1] * 2 - dh / 2), dw, dh);
  }
  function frame(sheet, w, h, line, index, cx, cy, shift) {
    put(sheet, (index % line) * w, Math.floor(index / line) * h, w, h, cx, cy, shift);
  }
  const shadow = drawIt => { ctx.globalAlpha = 0.55; drawIt(); ctx.globalAlpha = 1; };
  const icon = (name, cx, cy, size = 32) => size === 32
    ? put(img[name], 64, 0, 32, 32, cx, cy)
    : put(img[name], 0, 0, 64, 64, cx, cy, [0, 0], size, size);
  // A sprite stretched from point b (its bottom) to point a (its top), in pixels.
  function segment(sheet, a, b, width) {
    const dx = a[0] - b[0], dy = a[1] - b[1], length = Math.max(4, Math.hypot(dx, dy));
    ctx.save();
    ctx.translate((a[0] + b[0]) / 2, (a[1] + b[1]) / 2);
    ctx.rotate(Math.atan2(dx, -dy));
    ctx.drawImage(sheet, -width / 2, -length / 2, width, length);
    ctx.restore();
  }

  const present = (belt, i, f) => {
    const s = belt.slots[i - belt.first];
    if (!s) return false;
    let v = s[0] === 1;
    for (let k = 1; k < s.length && s[k] <= f; k++) v = !v;
    return v;
  };
  const beltX = (belt, i, f) => {
    const d = i * S.gap + S.speed * f;
    return belt.direction > 0 ? d : W - d;
  };
  const ease = t => t * t * (3 - 2 * t);
  function swingOf(f, phase) {
    const c = (f + phase) % CYCLE;
    if (c < 20) return 1 - ease(c / 20);
    if (c < 24) return 0;
    if (c < 44) return ease((c - 24) / 20);
    return 1;
  }

  function draw(f) {
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.drawImage(ground, 0, 0);
    for (const x of S.assemblers) shadow(() => put(img.amShadow, 0, 0, 54, 160, x, 3.5, [45.5, 0.5]));
    for (const x of S.labs) shadow(() => put(img.labShadow, 0, 0, 242, 136, x, 11.5, [13, 11]));
    for (const [row, name] of [[0, 'gear'], [0, 'plate'], [1, 'science']]) {
      const belt = S.belts[name];
      const index = row * 16 + (2 * f) % 16;
      for (let tx = -1; tx < 29; tx++) frame(img.belt, 128, 128, 16, index, tx + 0.5, belt.y);
      for (let i = belt.first; i < belt.first + belt.slots.length; i++) {
        const px = beltX(belt, i, f);
        if (px > -40 && px < W + 40 && present(belt, i, f)) icon(name, px / T, belt.y + (((i % 2) + 2) % 2 ? 0.25 : -0.25));
      }
    }
    // Ground-level objects back to front, inserter arms above them, as the game layers them.
    const objects = [];
    S.assemblers.forEach((x, k) => objects.push([3.5, () => {
      put(img.amBase, 0, 0, 200, 188, x, 3.5, [0, 2.5]);
      frame(img.amAnim, 148, 158, 8, (S.asmStart[k] + Math.floor(f * 2 / 3)) % 64, x, 3.5, [1.5, -15.5]);
    }]));
    S.labs.forEach((x, k) => objects.push([11.5, () => {
      put(img.labPatch, 0, 0, 242, 162, x, 11.5, [0, 15.5]);
      frame(img.lab, 194, 174, 11, (S.labStart[k] + Math.floor(f * 33 / 96)) % 33, x, 11.5, [0, 1.5]);
    }]));
    for (const s of S.inserters) objects.push([s.y, () => frame(img.platform, 105, 79, 4, s.pickup === 'south' ? 0 : 2, s.x, s.y, [1.5, 6.5])]);
    for (const [x, y] of S.poles) objects.push([y, () => put(img.pole, 0, 0, 72, 220, x, y, [0, -21])]);
    objects.sort((a, b) => a[0] - b[0]).forEach(o => o[1]());
    for (const s of S.inserters) {
      const swing = swingOf(f, s.phase), holding = s.holding[f] === '1';
      const dropDir = s.pickup === 'south' ? -1 : 1;
      const angle = swing * Math.PI, reach = (1.2 - 0.2 * swing) * T;
      const pivot = [s.x * T + 2, s.y * T - 8];
      const hand = [pivot[0] + Math.sin(angle) * reach * 0.9, s.y * T + dropDir * Math.cos(angle) * reach];
      const elbow = [(pivot[0] + hand[0]) / 2, (pivot[1] + hand[1]) / 2 - 0.42 * T];
      segment(img.arm, pivot, elbow, 16);
      segment(holding ? img.handClosed : img.handOpen, hand, elbow, 36);
      if (holding) icon(s.item, hand[0] / T, hand[1] / T, 26);
    }
    ctx.drawImage(wires, 0, 0);
    for (const [direction, y, phase] of S.robots) {
      const span = W + 6 * T, travel = ((f / FRAMES + phase) % 1) * span;
      const x = direction === 12 ? W + 3 * T - travel : travel - 3 * T;
      const bob = 3 * Math.sin(2 * Math.PI * (f / 48 + phase));
      shadow(() => frame(img.robotShadow, 104, 49, 16, direction, x / T, y, [33.5, 18.75]));
      put(img.robot, direction * 66, 76, 66, 76, x / T, y - 0.9 + bob / T, [0, -4.5]);
    }
  }

  // 30 frames a second, drawn only when the frame changes.
  const t0 = performance.now();
  let running = false, shown = -1;
  const tick = () => {
    if (!running) return;
    const f = Math.floor((performance.now() - t0) / 1000 * FPS) % FRAMES;
    if (f !== shown) { draw(f); shown = f; }
    requestAnimationFrame(tick);
  };
  return {
    resume() { if (!running) { running = true; requestAnimationFrame(tick); } },
    pause() { running = false; },
  };
}
