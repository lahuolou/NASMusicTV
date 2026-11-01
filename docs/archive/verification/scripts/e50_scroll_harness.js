#!/usr/bin/env node
/* ============================================================================
   E50「千里江山」纯数学 / 预算 harness —— §七·§九·§十 数字的唯一再生来源
   在 Node 的 vm 沙箱里桩掉 DOM/canvas，载入 docs/e50-qianli-jiangshan-preview.html
   的**原文脚本**，驱动真实页面代码。无图形设备、无 Math.random（页面用 mulberry32 种子）
   ⇒ 同帧序必产同数字，Kotlin 侧可逐位对账。

   用法：
     node docs/archive/verification/scripts/e50_scroll_harness.js
     node .../e50_scroll_harness.js --frames 3600
     node .../e50_scroll_harness.js --tier LOW

   ⛔ 分工（方案 §8.1）：**要看栅格化结果的门禁不在这里**（G1 逐字节对账、G4 像素哈希、
      G8 像素色域、G12 锚点读段）——那些由 e50_scroll_driver.mjs 在真浏览器里出数。
      本脚本只产"算得出的"数：几何闭合、周期、速度比与限速、α 区间与下限、每帧 ops/铺屏、
      位图字节账、印面坐标、带区闭合路径的形状守卫。
      ⛔ 文档里这些数字一律从这里抄，不许手抄屏幕（E44 偏差 D24 的成因）。
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HERE = path.dirname(process.argv[1]);
const ROOT = path.resolve(HERE, '..', '..', '..', '..');
const PAGE = path.join(ROOT, 'docs', 'e50-qianli-jiangshan-preview.html');
const OUT = path.join(ROOT, 'docs', 'archive', 'verification', 'results', 'e50_scroll_harness_final.txt');
const args = process.argv.slice(2);
const opt = (n, d) => { const i = args.indexOf('--' + n); return i >= 0 && i + 1 < args.length ? args[i + 1] : d; };
const FRAMES = parseInt(opt('frames', '1800'), 10);
const ONE_TIER = opt('tier', null);
const TIERS = ONE_TIER ? [ONE_TIER] : ['LOW', 'MEDIUM', 'HIGH'];

/* ── 抽原文脚本 ─────────────────────────────────────────────────────────── */
const html = fs.readFileSync(PAGE, 'utf8');
const sm = html.indexOf('<script>'), se = html.lastIndexOf('</script>');
if (sm < 0 || se <= sm) throw new Error('原型里未找到 <script> 段');
const script = html.slice(sm + 8, se);

/* ── DOM / canvas 桩：每次绘制都记进 cv._ops（H9 的形状守卫要吃这个）──────── */
const elements = new Map();
function mkEl(id) {
  const el = { id, style: {}, className: '', textContent: '', innerHTML: '', value: '', checked: false,
    min: 0, max: 0, step: 0, type: '', width: 0, height: 0,
    onclick: null, oninput: null, onchange: null,
    appendChild: () => {}, addEventListener: () => {},
    classList: { add: () => {}, remove: () => {}, toggle: () => {} },
    getContext: () => mkCtx(el) };
  return el;
}
for (const m of html.matchAll(/<(\w+)\b([^>]*)>/g)) {
  const attrs = {};
  for (const a of m[2].matchAll(/(\w+)="([^"]*)"/g)) attrs[a[1]] = a[2];
  if (!attrs.id) continue;
  const el = mkEl(attrs.id);
  if (m[1] === 'canvas') { el.width = +(attrs.width || 0); el.height = +(attrs.height || 0); }
  if (attrs.value !== undefined) el.value = attrs.value;
  el.checked = 'checked' in attrs;
  if (attrs.selected !== undefined) el.selected = true;
  elements.set(attrs.id, el);
}
function mkCanvas(w, h) {
  const cv = { width: w, height: h, _isCanvas: true, _ops: [], toDataURL: () => 'data:,' };
  cv.getContext = () => mkCtx(cv);
  return cv;
}
function mkCtx(cv) {
  const ops = cv._ops || (cv._ops = []);
  const grad = () => ({ addColorStop: (p, c) => ops.push(['stop', p, c]) });
  const P = (n) => (...a) => ops.push([n, ...a]);
  return {
    canvas: cv, _ops: ops,
    globalAlpha: 1, globalCompositeOperation: 'source-over',
    fillStyle: '#000', strokeStyle: '#000', lineWidth: 1, lineCap: 'butt', lineJoin: 'miter',
    save: P('save'), restore: P('restore'), translate: P('translate'), scale: P('scale'),
    setTransform: P('setTransform'), transform: P('transform'), rotate: P('rotate'),
    beginPath: P('beginPath'), closePath: P('closePath'), moveTo: P('moveTo'), lineTo: P('lineTo'),
    quadraticCurveTo: P('quadraticCurveTo'), bezierCurveTo: P('bezierCurveTo'),
    arc: P('arc'), ellipse: P('ellipse'), rect: P('rect'),
    fill: P('fill'), stroke: P('stroke'), clip: () => { throw new Error('HARD: 沙箱里出现了 clip'); },
    fillRect: P('fillRect'), strokeRect: P('strokeRect'), clearRect: P('clearRect'),
    drawImage: (...a) => ops.push(['drawImage', a[0] && a[0].width, a[0] && a[0].height, ...a.slice(1)]),
    createLinearGradient: grad, createRadialGradient: grad,
    createPattern: (c) => ({ _pattern: c }),
    measureText: (s) => ({ width: String(s).length * 6 }),
    getImageData: (x, y, w, h) => ({ data: new Uint8ClampedArray(Math.max(1, w) * Math.max(1, h) * 4), width: w, height: h }),
    putImageData: () => {},
  };
}
function boot(){
  const stage = elements.get('stage');
  const raf = [];
  const document = {
    getElementById: (id) => elements.get(id) || null,
    querySelectorAll: () => [],
    createElement: (t) => (t === 'canvas' ? mkCanvas(1, 1) : mkEl('_x')),
    addEventListener: () => {},
  };
  const window = { innerWidth: 1920, innerHeight: 1080, devicePixelRatio: 1, addEventListener: () => {} };
  window.document = document;
  const sandbox = {
    document, window, Math, JSON, Date, console, Set, Map,
    performance: { now: () => 0 },
    requestAnimationFrame: (cb) => { raf.push(cb); return raf.length; },
    cancelAnimationFrame: () => {}, setTimeout, clearTimeout,
    Float32Array, Float64Array, Uint8Array, Uint8ClampedArray, Uint16Array, Uint32Array,
    Int8Array, Int16Array, Int32Array, Array, Object, Number, String, Boolean, Error,
  };
  vm.createContext(sandbox);
  vm.runInContext(script, sandbox, { filename: 'e50-qianli-jiangshan-preview.html:script' });
  return { sandbox, raf };
}

/* ── 沙箱自检（D24 口径：目标变量没进上下文全局就抛，不许静默读残值）──────── */
const { sandbox } = boot();
const E = sandbox.window && sandbox.window.__E50;
if (!E) throw new Error('原型未导出 window.__E50（探针挂错作用域）');
for (const k of ['LW', 'LH', 'SAFE', 'HORIZON', 'BASE_PX_S', 'PERIOD_W', 'ENERGY_GAIN', 'ALPHA', 'FLOOR',
                 'ridgeSeries', 'ridgeY', 'pnoise', 'pnoiseTable', 'bakeRidge', 'fillBand', 'strokePts',
                 'SC', 'SKY_STOPS', 'sealRect', 'sealSize', 'ST', 'smooth']) {
  if (E[k] === undefined) throw new Error('沙箱自检失败：__E50.' + k + ' 不在上下文全局里');
}
if (!E.bake || !E.bake.mid || !E.bake.mid.cv) throw new Error('沙箱自检失败：BAKE.mid 未就绪');

const R = [];                       // 报告行
const say = (s) => { R.push(s); console.log(s); };
let FAILS = 0;
const check = (name, ok, detail) => { if (!ok) FAILS++; say(`  ${ok ? 'PASS' : 'FAIL'} ${name} —— ${detail}`); };

/* ══ H1 烘焙位图账（§7.2 的实测列，三档）════════════════════════════════ */
say('\n── H1 位图账（ARGB8888 字节 = w·h·4）──');
const BYTES = {};
for (const tier of TIERS) {
  E.tier = tier;                                    // setter 里已 rebake
  const keys = ['far', 'fog', 'mid', 'glint', 'water', 'waterHi', 'motes', 'near'];
  let total = E.sky.cv.width * E.sky.cv.height * 4 + E.grade.cv.width * E.grade.cv.height * 4
            + E.sealBitmap.cv.width * E.sealBitmap.cv.height * 4;
  const dims = [];
  for (const k of keys) { const c = E.bake[k].cv; total += c.width * c.height * 4; dims.push(`${k} ${c.width}×${c.height}`); }
  BYTES[tier] = total;
  say(`  [${tier}] 合计 ${(total / 1048576).toFixed(2)} MB（含 L0 天幕 ${E.sky.cv.width}×${E.sky.cv.height}、` +
      `grade ${E.grade.cv.width}×${E.grade.cv.height}、印 ${E.sealBitmap.cv.width}×${E.sealBitmap.cv.height}）`);
  say(`        ${dims.join(' · ')}`);
}
/* §7.2 的 target ≈41 MB 是**估算**（带高按目测填的）；实测容差取 46 MB = target ×1.12。
 *    判据：三档都必须进容差 ⇒ HIGH 破线即为真风险项（API 22–25 位图在 native heap，
 *    本机 dumpsys 实测过 Native Heap Free 只剩个位数 MB），⛔ 不许把数调宽来凑绿。 */
const MB_LIMIT = 46;
check('H1 位图预算 ≤ §7.2 target 容差 ' + MB_LIMIT + ' MB',
  TIERS.every(t => BYTES[t] <= MB_LIMIT * 1048576),
  `${TIERS.map(t => `${t} ${(BYTES[t] / 1048576).toFixed(2)}MB`).join(' ')}（估算 target ≈41 MB）` +
  (TIERS.some(t => BYTES[t] > MB_LIMIT * 1048576)
    ? ' ⛔ 破线 ⇒ 走 §7.2 缓解项：①L0/grade 改 RGB_565 ②L2 光晕降 0.5× ③缩周期 W（O6 待裁）' : ''));

/* ══ H2 图层几何表（Kotlin 烘焙输入：tile 尺寸 + 显示周期 + 带区）════════ */
say('\n── H2 图层几何表（tile 像素 / 显示周期 / 带区 y0–y1）──');
for (const tier of ['HIGH']) {
  E.tier = tier;
  const D = E.layerDef;
  for (const k of ['far', 'fog', 'mid', 'glint', 'water', 'waterHi', 'motes', 'near']) {
    const d = D[k], c = E.bake[k].cv;
    /* waterHi 与 water 同周期（页面 buildLayers 里 waterHi.period = P('water')），
     *    PERIOD_W 表里没有 waterHi 这一项 ⇒ 必须映射，否则这里量出 NaN 假红。 */
    const pw = E.PERIOD_W[k === 'waterHi' ? 'water' : k];
    const devPeriod = c.width / E.SC[tier], band = (d.y1 - d.y0) * E.LH;
    say(`  ${k.padEnd(8)} tile ${String(c.width).padStart(5)}×${String(c.height).padStart(4)}  ` +
        `显示周期 ${devPeriod.toFixed(0)}px (=PERIOD_W·LW ${(pw * E.LW).toFixed(0)})  ` +
        `带区 ${(d.y0 * E.LH).toFixed(0)}–${(d.y1 * E.LH).toFixed(0)} 高 ${band.toFixed(0)}  ` +
        `tile高/sc ${(c.height / E.SC[tier]).toFixed(0)}`);
    check(`H2 ${k} 显示尺寸与带区一致`,
      Math.abs(devPeriod - pw * E.LW) <= 1 && Math.abs(c.height / E.SC[tier] - band) <= 1.5,
      `周期 ${devPeriod.toFixed(1)} vs ${pw * E.LW}，tile高/sc ${(c.height / E.SC[tier]).toFixed(1)} vs 带高 ${band.toFixed(1)}`);
  }
}

/* ══ H3 周期性（纯数学，⛔ 不涉像素）════════════════════════════════════ */
say('\n── H3 周期性 ──');
{ E.tier = 'HIGH'; const D = E.layerDef;
  for (const k of ['far', 'mid', 'near']) {
    const d = D[k], W = Math.round(d.period);
    const s = E.ridgeSeries(d.seed, d.K, d.decay);
    let bad = 0;
    for (let i = 0; i < 400; i++) { const x = i * 23.7;
      if (Math.abs(E.ridgeY(x, W, s, 0, 1) - E.ridgeY(x + W, W, s, 0, 1)) > 1e-9) bad++; }
    const nt = E.pnoiseTable(d.seed + 77, d.lattice);
    let nbad = 0;
    for (let i = 0; i < 400; i++) { const x = i * 11.3;
      if (Math.abs(E.pnoise(nt, x, W) - E.pnoise(nt, x + W, W)) > 1e-12) nbad++; }
    /* ⚠️ 闭合性来自 pnoise 的**归一化写法**：`u = (x/W)·N` ⇒ 一个周期恰好铺 N 格，
     *    `N` 是**格数**（整数）而不是格宽。所以 `W/N` 不必是整数（mid 实测 3840/28=137.14），
     *    上一版把"W/lattice 必须是整数"当判据是**读错了实现**——那条性质与本层无关。
     *    ⭐ Kotlin 侧必须同形制：`u = x / W * N`、格点 `mod N`，⛔ 不要写成 `floor(x / cellW)`。 */
    const cellsOk = Number.isInteger(d.lattice) && d.lattice > 0;
    const seamSame = Math.abs(E.pnoise(nt, 0, W) - E.pnoise(nt, W, W)) < 1e-12;
    check(`H3 ${k} 峰脊+噪声闭合`, bad === 0 && nbad === 0 && cellsOk && seamSame,
      `傅里叶越界 ${bad}/400 · 周期噪声越界 ${nbad}/400 · 格数 N=${d.lattice}（整数 ${cellsOk}）` +
      `· 缝两侧同值 ${seamSame} · 格宽 ${(W / d.lattice).toFixed(2)}px（⛔ 不必整除）`);
  } }

/* ══ H4 速度表与限速（§4.3）════════════════════════════════════════════ */
say('\n── H4 速度 ──');
{ const B = E.BASE_PX_S, kMax = 1 + E.ENERGY_GAIN;
  const ratio = `${(B.far / B.far).toFixed(2)}:${(B.mid / B.far).toFixed(2)}:${(B.near / B.far).toFixed(2)}`;
  check('H4 视差比 1:2.5:5.5', Math.abs(B.mid / B.far - 2.5) < 1e-9 && Math.abs(B.near / B.far - 5.5) < 1e-9,
    `远:中:近 = ${ratio} · px/s ${JSON.stringify(B)} · 限速上界 ${kMax.toFixed(2)}×`);
  check('H4 需求区间(远3–6 中8–15 近18–30)', B.far >= 3 && B.far <= 6 && B.mid >= 8 && B.mid <= 15 && B.near >= 18 && B.near <= 30,
    `far ${B.far} mid ${B.mid} near ${B.near}`);
  // 逐帧位移上限：base·k/60，⛔ 任何一层都不许超 1.30× 基速
  let over = 0;
  for (const key in B) if (B[key] * kMax / 60 > B[key] / 60 * 1.30 + 1e-9) over++;
  check('H4 单帧位移 ≤1.30× 基速', over === 0, `越界层数 ${over} · 最大单帧位移 ${(Math.max(...Object.values(B)) * kMax / 60).toFixed(2)}px`); }

/* ══ H5 α 区间与下限（§5.5，⛔ 允许为下限但⛔ 不许低于下限）═════════════ */
say('\n── H5 α 区间 ──');
{ const KEYS = ['bass', 'mid', 'treble', 'energy', 'section', 'pulse'];
  const corners = [];
  const zero = Object.fromEntries(KEYS.map(k => [k, 0]));
  corners.push(zero);
  corners.push(Object.fromEntries(KEYS.map(k => [k, 1])));
  for (const k of KEYS) corners.push(Object.fromEntries(KEYS.map(x => [x, x === k ? 1 : 0])));
  const CEIL = { fog: 0.30, glint: 0.34, water: 0.34, waterHi: 0.30, motes: 0.22 };
  for (const k of ['fog', 'glint', 'water', 'waterHi', 'motes']) {
    const vals = corners.map(c => E.ALPHA[k](c));
    const lo = Math.min(...vals), hi = Math.max(...vals);
    check(`H5 ${k} α ∈ [下限, 上限]`,
      Math.abs(lo - E.FLOOR[k]) < 1e-9 && hi <= CEIL[k] + 1e-9 && hi > lo,
      `${lo.toFixed(3)}–${hi.toFixed(3)}（下限 ${E.FLOOR[k]}、方案上限 ${CEIL[k]}）`);
  }
  for (const k of ['far', 'mid', 'near']) check(`H5 ${k} α 恒 1（硬边层不参与律动）`, E.ALPHA[k](corners[0]) === 1 && E.ALPHA[k](corners[1]) === 1,
    `α=${E.ALPHA[k](corners[1])}`); }

/* ══ H6 每帧图元账 / 铺屏（§7.1 实测列）════════════════════════════════ */
say(`\n── H6 图元账（${FRAMES} 帧推进）──`);
const OPS = {};
for (const tier of TIERS) {
  E.tier = tier; E.src = 'music'; E.restart();
  let maxOps = 0, sumOps = 0, maxFill = 0, sumFill = 0;
  for (let i = 0; i < FRAMES; i++) { E.step(1);
    maxOps = Math.max(maxOps, E.ops); sumOps += E.ops;
    maxFill = Math.max(maxFill, E.fill); sumFill += E.fill; }
  OPS[tier] = { maxOps, avgOps: sumOps / FRAMES, maxFill, avgFill: sumFill / FRAMES };
  say(`  [${tier}] ops 峰值 ${maxOps} 均值 ${OPS[tier].avgOps.toFixed(2)} · 铺屏 峰值 ${maxFill.toFixed(2)} 均值 ${OPS[tier].avgFill.toFixed(2)} 屏`);
}
{ const tgt = { HIGH: 12, MEDIUM: 11, LOW: 10 };
  for (const t of TIERS) check(`H6 ${t} ops ≤ §7.1 目标`, OPS[t].maxOps <= 14 && OPS[t].avgOps <= tgt[t] + 0.001,
    `峰值 ${OPS[t].maxOps}（硬顶 14）· 均值 ${OPS[t].avgOps.toFixed(2)}（目标 ≤${tgt[t]}）`); }

/* ══ H7 offset 有界与滚动连续性（长卷不得倒退/瞬移）════════════════════ */
say('\n── H7 滚动连续性 ──');
{ E.tier = 'HIGH'; E.src = 'music'; E.restart();
  const keys = Object.keys(E.BASE_PX_S);
  let prev = null, maxStep = 0, out = 0, neg = 0;
  for (let i = 0; i < FRAMES; i++) {
    E.step(1);
    const o = E.offsets;
    for (const k of keys) {
      if (o[k] < -1e-9) neg++;
      if (o[k] >= E.PERIOD_W[k] * E.LW) out++;
      if (prev) { let d = o[k] - prev[k]; if (d < -E.PERIOD_W[k] * E.LW / 2) d += E.PERIOD_W[k] * E.LW;
        maxStep = Math.max(maxStep, d); }
    }
    prev = Object.assign({}, o);
  }
  const baseMax = Math.max(...keys.map(k => E.BASE_PX_S[k])) * (1 + E.ENERGY_GAIN) / 60;
  check('H7 offset 始终在 [0, 周期) 内', out === 0 && neg === 0, `越界 ${out} · 负值 ${neg}`);
  check('H7 单帧步进 ≤ 限速后的最大基速', maxStep <= baseMax + 1e-6,
    `最大单帧步进 ${maxStep.toFixed(3)}px ≤ 理论上限 ${baseMax.toFixed(3)}px（near 22px/s·1.30/60）`);
  check('H7 glint 与 mid 同 offset（同层同速）', E.offsets.glint === E.offsets.mid,
    `mid ${E.offsets.mid.toFixed(3)} glint ${E.offsets.glint.toFixed(3)}`); }

/* ══ H8 几何不随频谱变（D5，纯数学：山脊数组逐位一致）════════════════ */
say('\n── H8 几何不动（频谱扫遍）──');
{ E.tier = 'HIGH'; E.src = 'manual'; E.restart();
  const sig = () => [E.bake.mid.ridge, E.bake.far.ridge, E.bake.near.ridge]
    .map(a => a.reduce((h, v) => (Math.imul(h, 31) + Math.round(v * 1000)) >>> 0, 2166136261)).join(',');
  const h0 = sig();
  const KEYS = ['bass', 'mid', 'treble', 'energy', 'section', 'pulse'];
  let bad = 0;
  for (let i = 0; i <= 20; i++) { for (const k of KEYS) E.manualSet(k, i / 20); E.step(4); if (sig() !== h0) bad++; }
  E.rebake(); const rebakeSame = sig() === h0;
  check('H8 满谱扫遍 + 重烘后几何逐位一致', bad === 0 && rebakeSame,
    `哈希变化 ${bad}/21 · 满谱重烘 ${rebakeSame ? '一致 ✅' : '被污染 ❌'} · mid 顶点 ${E.bake.mid.vertices} 个`); }

/* ══ H9 带区闭合路径守卫（fillBand：脊线平滑、闭合边必须直线）══════════ */
say('\n── H9 闭合路径守卫 ──');
{ const mkc = () => { const c = mkCanvas(10, 10); return [c, c.getContext('2d')]; };
  const pts = [[0, 20], [10, 12], [20, 16], [30, 8], [40, 14]];
  const [c, x] = mkc();
  E.fillBand(x, pts, 100);
  const seq = c._ops.map(o => o[0]).join(' ');
  const tail = c._ops.slice(-4);
  const closingStraight = tail.every(o => o[0] === 'lineTo' || o[0] === 'closePath');
  const quadCount = c._ops.filter(o => o[0] === 'quadraticCurveTo').length;
  const bottomY = tail.filter(o => o[0] === 'lineTo').map(o => o[2]).filter(v => v === 100).length;
  check('H9 闭合边是直线（⛔ 平滑器不吃闭合角点）',
    seq === 'beginPath moveTo quadraticCurveTo quadraticCurveTo quadraticCurveTo lineTo lineTo lineTo closePath' &&
      closingStraight && bottomY === 2,
    `op 序 ${seq} · 底部直线点 ${bottomY} 个 · 二次段 ${quadCount}（须 pts-2=3）· 尾部全直线 ${closingStraight}`);
  // ⭐ 反例（必须真的咬得住）：**同一组含闭合角点的点集**交给 strokePts，
  //    角点会被喂进中点二次平滑器 ⇒ 出现"控制点或端点落在带底 y=100 的二次段"
  //    ⇒ 底边不再是直线，而是扫回画布中部的一条曲线（就是那次 8 万坏字节的成因）。
  //    fillBand 的二次段必须**一个都不碰带底**。
  const band = pts.concat([[50, 100], [0, 100]]);     // 脊线 + 两条闭合角点（x 与脊点不重合，排除巧合）
  const quadAtBottom = (ops) => ops.filter(o => o[0] === 'quadraticCurveTo' &&
    (o[2] === 100 || o[4] === 100)).length;
  const [c3, x3] = mkc(); E.strokePts(x3, band, true);
  const qBad = quadAtBottom(c3._ops), qGood = quadAtBottom(c._ops);
  check('H9 反例咬合：strokePts 会把闭合角点平滑化', qBad >= 1 && qGood === 0,
    `strokePts(闭合点集) 有 ${qBad} 段二次贴带底（须 ≥1，否则本条判据失去咬合力）· fillBand ${qGood} 段（须 0）` +
    ` · strokePts 尾部 ${JSON.stringify(c3._ops.slice(-3).map(o => o[0] + '(' + o.slice(1).join(',') + ')'))}`); }

/* ══ H10 印面几何（§6.3，⛔ 不参与滚动/缩放）══════════════════════════ */
say('\n── H10 印面 ──');
{ E.tier = 'HIGH'; E.src = 'music'; E.restart();
  const r0 = E.sealRect();
  E.step(300);
  const r1 = E.sealRect();
  const inset = Math.max(E.SAFE * Math.min(E.LW, E.LH), 30);
  const pct = r1.w / E.LH;
  check('H10 滚动 300 帧后坐标零变化', JSON.stringify(r0) === JSON.stringify(r1), `${JSON.stringify(r0)} vs ${JSON.stringify(r1)}`);
  check('H10 尺寸 ∈ 8–10%H', pct >= 0.08 && pct <= 0.10, `${(pct * 100).toFixed(1)}%H（s=0.09·H）`);
  check('H10 落在安全区内', r1.x >= inset - 1e-9 && r1.y >= inset - 1e-9 && r1.x + r1.w <= E.LW - inset + 1e-9,
    `inset ${inset.toFixed(0)}px · 距右 ${(E.LW - r1.x - r1.w).toFixed(0)}px · 距上 ${r1.y.toFixed(0)}px`);
  check('H10 三档尺寸同一（⛔ 不参与缩放）', TIERS.every(t => { E.tier = t; return Math.abs(E.sealSize() - 0.09 * E.LH) < 1e-9; }),
    `sealSize = ${E.sealSize().toFixed(2)}px（各档一致）`); }

/* ══ H11 α 单帧跳变上限（§5.4 / G9 的纯数学镜像）═════════════════════ */
say('\n── H11 α 平滑 ──');
{ E.tier = 'HIGH'; E.src = 'music'; E.restart();
  /* ⚠️ 与页面 G9 **同口径**：只看稳态（前 90 帧是入场爬升，⛔ 不算抖动）。 */
  E.step(90);
  const RNG = { fog: [0.10, 0.30], glint: [0.10, 0.34], water: [0.16, 0.34],
                waterHi: [0.10, 0.30], motes: [0.05, 0.22] };
  const last = {}, worst = {};
  for (const k in RNG) worst[k] = 0;
  for (let i = 0; i < FRAMES - 90; i++) {
    E.step(1);
    for (const k of Object.keys(RNG)) {
      const rec = E.ST[k];
      if (!rec || typeof rec.alpha !== 'number') continue;
      if (last[k] !== undefined) worst[k] = Math.max(worst[k], Math.abs(rec.alpha - last[k]));
      last[k] = rec.alpha;
    }
  }
  const lim = Object.fromEntries(Object.entries(RNG).map(([k, [a, b]]) => [k, (b - a) * 0.06]));
  const bad = Object.keys(RNG).filter(k => worst[k] > lim[k] + 1e-9);
  check('H11 任一 α 单帧变化 ≤ 动态范围 6%', bad.length === 0,
    Object.keys(RNG).map(k => `${k} ${(worst[k] * 100).toFixed(2)}%/限 ${(lim[k] * 100).toFixed(2)}%`).join(' · ') +
    (bad.length ? ` ⛔ ${bad.join(',')}` : '')); }

/* ══ H13 字形表健全性（⭐ 纯数据门禁：挡"表本身没画对"，⛔ 不管美不美）══════
 * 背景：印章那一轮的真实失效链是 —— 机制门（坐标/尺寸/落墨比）全绿，而放大判读出来
 *       是"四条不相干的弯钩"。G6 后来补了落墨比双向门，但那要像素（只有真浏览器跑得动）。
 *       本条把**不需要像素**的那部分前移到沙箱：字形表如果是空的、笔画溢出 10×10 格、
 *       包围盒只占半格（= 印面上的一粒米）、或四个字里有两字是同一套笔画，
 *       在这里就红，不必等到判读。
 * ⚠️ 阈值口径：≥5 笔（最少的「地」6 笔）、bbox ≥7/10（缪篆"撑满方格"的结体要求）、
 *    坐标 ∈[0,10]（越界即画到印外）。⛔ 不许把 7 调低来迁就某个字 —— 调低等于放弃该判据。 */
say('\n── H13 字形表 ──');
{ const G = E.SEAL_GLYPHS, keys = Object.keys(G);
  const box = (strokes) => { let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
    for (const p of strokes) for (const q of p){ x0 = Math.min(x0, q[0]); y0 = Math.min(y0, q[1]);
      x1 = Math.max(x1, q[0]); y1 = Math.max(y1, q[1]); }
    return { w: x1 - x0, h: y1 - y0 }; };
  const stat = keys.map(k => { const s = G[k].strokes;
    let oob = 0, degen = 0;
    for (const p of s){ if (p.length < 2) degen++; for (const q of p)
      if (!(q[0] >= 0 && q[0] <= 10 && q[1] >= 0 && q[1] <= 10)) oob++; }
    return { k, name: G[k].name, n: s.length, oob, degen, ...box(s),
             sig: JSON.stringify(s.map(p => p.map(q => q.join(',')).join(';'))) }; });
  const thin = stat.filter(o => o.n < 5);
  const spill = stat.filter(o => o.oob > 0 || o.degen > 0);
  const small = stat.filter(o => o.w < 7 || o.h < 7);
  const dup = stat.filter((o, i) => stat.some((p, j) => j < i && p.sig === o.sig));
  check('H13 每字 ≥5 笔', thin.length === 0,
    stat.map(o => `${o.name}${o.n}笔`).join(' ') + (thin.length ? ` ⛔ ${thin.map(o => o.k).join(',')}` : ''));
  check('H13 坐标全在 0–10 格内 · 无单点笔画', spill.length === 0,
    stat.map(o => `${o.k} 越界${o.oob}/退化${o.degen}`).join(' ') + (spill.length ? ' ⛔' : ''));
  check('H13 每字包围盒 ≥7×7（缪篆撑满方格）', small.length === 0,
    stat.map(o => `${o.name} ${o.w.toFixed(1)}×${o.h.toFixed(1)}`).join(' ') + (small.length ? ' ⛔' : ''));
  check('H13 四字笔画集互不相同', dup.length === 0,
    dup.length ? `重复：${dup.map(o => o.name).join(',')}` : `四字签名哈希 ${stat.map(o => o.sig.length).join('/')}`);
  /* 负对照：一套**故意坏掉**的表（3 笔、bbox 只有 2×10、含一个格外坐标）必须被三条各自咬住。
   * ⛔ 只报"当前全绿"的判据可能恒真 —— 这里证明判据真的有区分能力。
   * ⚠️ 反例必须**每条判据单独成立**：上一版拿 [[0,0],[11,10]] 当反例，它的 bbox 宽 = 11
   *    （比阈值 7 还大）⇒ "bbox<7" 那条其实没咬住，只是碰巧被越界判据救回来 —— 恒真判据的
   *    典型形状。现反例 bbox=2×10（咬住"太小"）、x=−1（咬住"越界"）、3 笔（咬住"太少"）。 */
  const FAKE = [[[0, 0], [1, 10]], [[0, 5], [1, 5]], [[-1, 3], [1, 3]]];
  const fbox = box(FAKE);
  const fOob = FAKE.some(p => p.some(q => !(q[0] >= 0 && q[0] <= 10 && q[1] >= 0 && q[1] <= 10)));
  const bites = FAKE.length < 5 && Math.min(fbox.w, fbox.h) < 7 && fOob;
  check('H13 判据有咬合力（负对照三条各自落单）', bites,
    `反例：笔画数 ${FAKE.length}（须 <5 → ${FAKE.length < 5 ? '咬住' : '漏！'}）· ` +
    `bbox ${fbox.w.toFixed(1)}×${fbox.h.toFixed(1)}（须 <7 → ${Math.min(fbox.w, fbox.h) < 7 ? '咬住' : '漏！'}）· ` +
    `格外点（须有 → ${fOob ? '咬住' : '漏！'}）`); }

/* ══ H12 确定性：同帧序两遍，几何哈希逐位一致 ═════════════════════════ */
say('\n── H12 确定性 ──');
{ const run = () => { E.tier = 'MEDIUM'; E.src = 'music'; E.restart(); E.step(300);
    /* ⛔ 只哈希"算得出的"几何：bakeRidge/bakeNear 带 ridge 数组，bakeWater/bakeFog/bakeMotes
     *    只有 {cv, period}（沙箱里没有像素，位图内容无从比对）。 */
    return [E.bake.mid.ridge, E.bake.far.ridge, E.bake.near.ridge]
      .map(a => a.reduce((h, v) => (Math.imul(h, 31) + Math.round(v * 1000)) >>> 0, 2166136261)).join(',') +
      '|' + JSON.stringify(E.offsets); };
  const a = run(), b = run();
  check('H12 同帧序两遍逐位一致', a === b, a === b ? `哈希 ${a}` : `${a}\n      vs ${b}`); }

/* ── 汇总 ───────────────────────────────────────────────────────────────── */
say('\n' + '='.repeat(74));
say(`结论：${FAILS === 0 ? '✅ 全部通过' : '⛔ ' + FAILS + ' 项未通过'}`);
say(`位图账：${TIERS.map(t => `${t} ${(BYTES[t] / 1048576).toFixed(2)}MB`).join(' · ')}`);
say(`图元账：${TIERS.map(t => `${t} ops≤${OPS[t].maxOps}（均 ${OPS[t].avgOps.toFixed(2)}）铺屏≤${OPS[t].maxFill.toFixed(2)}`).join(' · ')}`);
fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, '# E50 千里江山 纯数学/预算 harness 权威输出\n' +
  `# 生成：node e50_scroll_harness.js --frames ${FRAMES}${ONE_TIER ? ' --tier ' + ONE_TIER : ''}\n` +
  `# 时间：${new Date().toISOString()}\n\n` + R.join('\n') + '\n', { encoding: 'utf8' });
console.log('\n权威输出已写入 ' + OUT);
process.exitCode = FAILS === 0 ? 0 : 1;
