// The launcher's moving background, behind its native panels. The app calls:
//   stage.show('blueprint' | 'build' | 'factory', {dim, saturate})   crossfades to that scene
//   stage.progress(percent)                                        builds the 3D scene that far
// Scenes load on first use; hidden ones stop drawing.

const scenes = {
  async blueprint(el) {
    el.innerHTML = await (await fetch('blueprint.svg')).text();
    return { resume() { el.firstElementChild.unpauseAnimations?.(); }, pause() { el.firstElementChild.pauseAnimations?.(); } };
  },
  async build(el) { return (await import('./build.js')).start(el); },
  async factory(el) { return (await import('./factory.js')).start(el); },
};
const loaded = {};
let current = null, lastProgress = 0;

function scene(name) {
  loaded[name] ??= scenes[name](document.getElementById(name)).catch(error => {
    console.error(`stage: ${name} failed: ${error.message}`);
    delete loaded[name];
    throw error;
  });
  return loaded[name];
}

window.stage = {
  async show(name, { dim = 1, saturate = 1 } = {}) {
    const el = document.getElementById(name);
    el.style.filter = dim === 1 && saturate === 1 ? '' : `brightness(${dim}) saturate(${saturate})`;
    if (current === name) return;
    let s;
    try { s = await scene(name); } catch {
      if (name === 'factory') return stage.show('blueprint');   // no sprites: the blueprint will do
      return;
    }
    if (name === 'build') s.progress(lastProgress);
    const previous = current;
    current = name;
    s.resume();
    el.classList.add('on');
    if (previous) {
      document.getElementById(previous).classList.remove('on');
      setTimeout(async () => { if (current !== previous) (await scene(previous)).pause(); }, 900);
    }
  },
  async progress(percent) {
    lastProgress = percent;
    if (loaded.build) (await scene('build')).progress(percent);
  },
  // The app is off screen: nothing draws.
  async sleep() { if (current) (await scene(current)).pause(); },
  async wake() { if (current) (await scene(current)).resume(); },
};
