// E50「千里江山 · 青绿长卷」原型的**真浏览器驱动**（headless Edge + CDP，端口 9350）。
//
// 分工（与 E43/E44/E46–E49 同口径）：
//   · 本脚本只做两件事：① 在**真实浏览器**里跑页面自带的 G1–G12 门禁（需要像素读回，
//     vm 沙箱给不了）；② 真实 rAF 跑 N 秒后截帧，交**所有者判读观感**。
//   · ⛔ 不要拿截图当数字判据；数字一律由 gates 输出的页内门禁原文再生。
//   · 纯数学对账（傅里叶闭合 / 周期性 / 速度比 / α 区间）走 e50_scroll_harness.js（vm 沙箱）。
//
// 用法：
//   node docs/archive/verification/scripts/e50_scroll_driver.mjs gates HIGH
//   node docs/archive/verification/scripts/e50_scroll_driver.mjs shot  HIGH med_run 12 music
//   node docs/archive/verification/scripts/e50_scroll_driver.mjs seal  HIGH
//   node docs/archive/verification/scripts/e50_scroll_driver.mjs seamdiag HIGH   # 一次性诊断，非门禁
//   node docs/archive/verification/scripts/e50_scroll_driver.mjs tiers 10
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, resolve } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9350;                                    // ⛔ 别和 seaside(9333)/moonlit(9334)/fog(9336)/snow(9337)/desert(9338)/flock(9349) 撞
const PAGE = resolve(ROOT, 'docs', 'e50-qianli-jiangshan-preview.html');
const URL_STR = 'file:///' + PAGE.replace(/\\/g, '/');
const OUT = resolve(ROOT, 'output', 'e50_frames');
const SHOT_W = 1920, SHOT_H = 1080;                   // 原型逻辑画幅 = 1920×1080（app 的 1080p 基准机）
const TIERS = ['LOW', 'MEDIUM', 'HIGH'];
const navUrl = () => URL_STR + '?v=' + Date.now();    // ⭐ 防文档缓存

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function reuse() {
  try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { await pageTarget(); return true; } }
  catch { /* 没开端口 */ }
  return false;
}
async function launch() {
  mkdirSync(OUT, { recursive: true });
  const args = [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    `--user-data-dir=${OUT}_profile`, `--remote-debugging-port=${PORT}`,
    `--window-size=${SHOT_W},${SHOT_H}`, '--force-device-scale-factor=1', '--hide-scrollbars',
    '--mute-audio', 'about:blank',
  ];
  const proc = spawn(EDGE, args, { stdio: 'ignore' });
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) return proc; } catch { /* 端口还没起 */ }
    await sleep(250);
  }
  throw new Error('Edge 没开出调试端口');
}
async function pageTarget() {
  for (let i = 0; i < 60; i++) {
    const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
    const p = list.find((t) => t.type === 'page');
    if (p) return p;
    await sleep(200);
  }
  throw new Error('没有 page target');
}
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) {
        const { resolve: res, reject: rej } = c.pending.get(m.id);
        c.pending.delete(m.id);
        m.error ? rej(new Error(m.error.message)) : res(m.result);
      }
    };
    return c;
  }
  send(method, params = {}) {
    return new Promise((res, rej) => {
      const id = ++this.id;
      this.pending.set(id, { resolve: res, reject: rej });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
}
const ev = (cdp, expr) => cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true })
  .then((r) => { if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails)); return r.result.value; });

async function navigateAndBoot(cdp) {
  await cdp.send('Page.enable');
  // 页内未捕获异常回传：顶层抛错会让 __E50 恒 undefined，只报"页面没起来"会伪装成真因
  await cdp.send('Page.addScriptToEvaluateOnNewDocument', {
    source: 'window.__BOOTERR=[];window.onerror=function(m,u,l,c){window.__BOOTERR.push(m+" @"+(u||"").split("/").pop()+":"+l+":"+c);return false;};'
  });
  await cdp.send('Page.navigate', { url: navUrl() });
  for (let i = 0; i < 120; i++) {
    if (await ev(cdp, `!!(window.__E50 && window.__E50.bake && typeof window.__E50.gates==='function')`).catch(() => false)) break;
    await sleep(500);
  }
  const booted = await ev(cdp, `!!(window.__E50 && window.__E50.bake && typeof window.__E50.gates==='function')`);
  if (!booted) {
    const errs = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`).catch(() => '[]');
    throw new Error('页面没起来（__E50 未就绪）。页内异常=' + errs);
  }
  const pre = await ev(cdp, `JSON.stringify(window.__BOOTERR||[])`);
  if (pre !== '[]') console.log('  ⚠️ 页内未捕获异常（帧可能仍出，但必须处理）：' + pre);
}

function fmtGates(rows) {
  const lines = rows.map((g) => `${g.ok ? 'PASS' : 'FAIL'} ${g.id} —— ${g.detail}`);
  const pass = rows.filter((g) => g.ok).length;
  return { lines, pass, total: rows.length };
}

/** 跑门禁：档位先切（重烘），逐档各跑一遍并汇总 */
async function gates(cdp, onlyTier) {
  const list = onlyTier ? [onlyTier] : TIERS;
  const all = [];
  for (const tier of list) {
    await ev(cdp, `(() => { const E = window.__E50; E.tier = '${tier}'; E.restart(); E.src = 'music'; return true; })()`);
    const rows = await ev(cdp, `window.__E50.gates()`);
    const r = fmtGates(rows);
    console.log(`\n[${tier}] self-gates ${r.pass}/${r.total}`);
    for (const l of r.lines) console.log('  ' + l);
    all.push(`## ${tier}\n` + r.lines.join('\n') + `\n→ ${r.pass}/${r.total} PASS\n`);
    if (r.pass !== r.total) console.log(`  ⛔ ${tier} 档有 FAIL，不得进入 Kotlin 落地`);
  }
  mkdirSync(resolve(HERE, '..', 'results'), { recursive: true });
  const txt = '# E50 千里江山 原型 G1–G12 门禁权威输出\n' +
    `# 生成：node e50_scroll_driver.mjs gates  页内 window.__E50.gates() 原文\n` +
    `# 时间：${new Date().toISOString()}\n\n` + all.join('\n');
  const f = resolve(HERE, '..', 'results', 'e50_scroll_gates_final.txt');
  writeFileSync(f, txt);
  console.log('\n权威输出已写入 ' + f);
  return all;
}

/** 真实 rAF 跑 waitSec 秒后截帧（观感判读用，⛔ 不产数字） */
async function shot(cdp, tier, name, waitSec, src) {
  mkdirSync(OUT, { recursive: true });
  await ev(cdp, `(() => { const E = window.__E50; E.tier = '${tier}'; E.src = '${src || 'music'}'; E.restart(); })()`);
  await sleep(waitSec * 1000);
  const info = await ev(cdp, `(() => { const E = window.__E50; return {
      t: (E.frameIdx/60).toFixed(1), ops: E.ops, fill: +E.fill.toFixed(3), scale: +E.lastScale.toFixed(4),
      off: { far:+E.offsets.far.toFixed(1), mid:+E.offsets.mid.toFixed(1), near:+E.offsets.near.toFixed(1) },
      shot: document.getElementById('stage').toDataURL('image/png').split(',')[1] }; })()`);
  const buf = Buffer.from(info.shot, 'base64');
  writeFileSync(`${OUT}/${name}.png`, buf);
  const sha = createHash('sha256').update(buf).digest('hex').slice(0, 16);
  SHOTS.push({ name, tier, sha });
  console.log(`  ${name}.png 档=${tier} t=${info.t}s ops=${info.ops} 铺屏=${info.fill} 呼吸=${info.scale} ` +
    `偏移 far/mid/near=${info.off.far}/${info.off.mid}/${info.off.near} sha=${sha}`);
  return info;
}
const SHOTS = [];

function reportDistinct() {
  if (SHOTS.length < 2) return;
  const seen = new Map();
  for (const s of SHOTS) {
    if (seen.has(s.sha)) console.log(`  ⛔⛔ 重复帧：${s.name} 与 ${seen.get(s.sha).name} sha 相同 ⇒ 画面没在动`);
    else seen.set(s.sha, s);
  }
  if (seen.size === SHOTS.length) console.log(`  ✅ ${SHOTS.length} 帧 sha 互不相同（长卷在流动）`);
}

/** 印面放大帧（可读性判读给所有者）。
 *  ⚠️ SEAL 烘出来的位图在页面闭包里、不对外暴露 ⇒ 这里用**探针暴露的字形表**（SEAL_GLYPHS /
 *  SEAL_LAYOUT）在画布外重绘一张放大图。判读的是**字形与布局**，⛔ 不是做旧像素。 */
async function seal(cdp, tier) {
  mkdirSync(OUT, { recursive: true });
  const png = await ev(cdp, `(() => {
    const E = window.__E50, s = E.sealSize();
    const c = document.createElement('canvas'); c.width = 512; c.height = 512;
    const x = c.getContext('2d');
    x.fillStyle = '#d9cbb0'; x.fillRect(0,0,512,512);
    const g = E.SEAL_GLYPHS, L = E.SEAL_LAYOUT, pad = Math.round(512*0.03), cell = (512-2*pad)*0.46, gap = (512-2*pad)*0.5, sc = cell/10;
    // ⚠️ 比例常量一律**从页面取**（pad=0.03s、SEAL_LW）：探针里另抄一份数字，改页面时探针不会跟着变，
    //    判读图就成了"我以为的样子"而不是"真机上的样子"。
    x.fillStyle = '#B03A2E'; x.fillRect(pad, pad, 512-2*pad, 512-2*pad);
    x.lineCap='round'; x.lineJoin='round'; x.strokeStyle='rgba(232,220,195,.96)'; x.lineWidth = sc*E.SEAL_LW;
    for (const [key, col, row] of L){
      const gx = pad + gap*col + gap*0.5, gy = pad + gap*row + gap*0.5;
      for (const pts of g[key].strokes){
        x.beginPath();
        const P = pts.map(p => [gx-cell/2+p[0]*sc, gy-cell/2+p[1]*sc]);
        x.moveTo(P[0][0],P[0][1]);
        for (let i=1;i<P.length-1;i++){ const mx=(P[i][0]+P[i+1][0])/2, my=(P[i][1]+P[i+1][1])/2; x.quadraticCurveTo(P[i][0],P[i][1],mx,my); }
        x.lineTo(P[P.length-1][0],P[P.length-1][1]); x.stroke();
      }
    }
    x.strokeStyle='rgba(232,220,195,.55)'; x.lineWidth=6;
    x.beginPath(); x.moveTo(256,pad); x.lineTo(256,512-pad); x.moveTo(pad,256); x.lineTo(512-pad,256); x.stroke();
    x.strokeStyle='rgba(120,40,30,.9)'; x.lineWidth=8; x.strokeRect(pad,pad,512-2*pad,512-2*pad);
    return { png: c.toDataURL('image/png').split(',')[1], info: { size: s, frac: s / E.LH } }; })()`);
  const buf = Buffer.from(png.png, 'base64');
  writeFileSync(`${OUT}/seal_${tier}.png`, buf);
  console.log(`  seal_${tier}.png 印面 ${png.info.size}px = ${(png.info.frac*100).toFixed(1)}%H（需求 8–10%）`);
  console.log('  ⚠️ 四字可读性**判读归所有者**，本脚本不打勾。');
}

/** 一次性诊断（⛔ 不是门禁）：G1 只报"差多少字节"，这里定位**差在哪几列/哪一行**，
 *  以及 glint 与 mid 的 ridge 首个不同下标。只看总数会误判成因（本仓反复踩过）。 */
async function seamdiag(cdp) {
  const out = await ev(cdp, `(() => {
    const E = window.__E50, D = E.layerDef, B = E.bake;
    const sc = {LOW:0.5,MEDIUM:0.75,HIGH:1.0}[E.tier], P = B.mid.period, Hm = B.mid.cv.height;
    const S2 = P >> 1, sh0 = S2;
    /** 只料 only（0=全部）下的相位平移对账：返回坏字节数 + 坏列/坏行分布 + 最大差位置。 */
    const diff = (only, sh) => {
      const S2 = sh === undefined ? sh0 : sh;
      const t1 = E.bakeRidge(D.mid, sc, only ? { only } : {});
      const t2 = E.bakeRidge(D.mid, sc, { shift: S2, only: only || undefined });
      const A = t1.cv.getContext('2d').getImageData(0, 0, P, Hm).data;
      const Bd = t2.cv.getContext('2d').getImageData(0, 0, t2.cv.width, t2.cv.height).data;
      const perCol = new Array(P).fill(0), perRow = new Array(Hm).fill(0), perColRows = new Array(P).fill(0);
      let bad = 0, worst = 0, wx = -1, wy = -1; const mag = { d1: 0, d2: 0, d4: 0, big: 0 };
      for (let c = 0; c < P; c++){ const src = (c + S2) % P;
        for (let y = 0; y < Hm; y++){ const ia = (y*P+src)*4, ib = (y*P+c)*4;
          let any = false;
          for (let k = 0; k < 4; k++){ const d = Math.abs(A[ia+k]-Bd[ib+k]);
            if (d){ bad++; any = true; perCol[c]++; perRow[y]++;
              if (d === 1) mag.d1++; else if (d <= 3) mag.d2++; else if (d <= 15) mag.d4++; else mag.big++;
              if (d > worst){ worst = d; wx = c; wy = y; } } }
          if (any) perColRows[c]++; } }
      const hist = {}; perColRows.forEach(n => { if (n) hist[n] = (hist[n] || 0) + 1; });
      const cols = []; perCol.forEach((n, x) => { if (n) cols.push(x); });
      const runs = []; let cur = null;
      for (const x of cols){ if (cur && x === cur[1] + 1) cur[1] = x; else { cur = [x, x]; runs.push(cur); } }
      const rows = []; perRow.forEach((n, y) => { if (n) rows.push(y); });
      // 逐列现场：**这一列自己**的差行落在脊线边界还是山体内部？（⛔ 不能用全局坏行列表配对，那会读到等值像素）
      let probe = null;
      if (only === 1){
        const st2 = Math.max(1, Math.round(sc * 2)), G2 = Math.max(24, Math.round(12 * sc));
        const at = (r, c) => r[Math.round((c + G2) / st2)];
        const one = (c, win) => { const src = (c + S2) % P, cells = [];
          for (let y = 0; y < Hm; y++){ const ia = (y*P+src)*4, ib = (y*P+c)*4;
            let any = false; for (let k = 0; k < 4; k++) if (A[ia+k] !== Bd[ib+k]) any = true;
            if (any) cells.push([y, [A[ia],A[ia+1],A[ia+2],A[ia+3]], [Bd[ib],Bd[ib+1],Bd[ib+2],Bd[ib+3]]]); }
          if (!win && cells.length) win = [cells[0][0] - 4, cells[0][0] + 2];   // 默认窗口=该列首个差行附近
          // win = [y0,y1] ⇒ 额外打印该列**无条件**的像素窗口（看清差行相对边界的位置）
          const strip = [];
          if (win) for (let y = Math.max(0, win[0]); y <= Math.min(Hm - 1, win[1]); y++){
            const ia = (y*P+src)*4, ib = (y*P+c)*4;
            strip.push([y, A[ia+3], Bd[ib+3], A[ia], Bd[ib]]);
          }
          return { c, src, y1: +at(t1.ridge, src).toFixed(2), y2: +at(t2.ridge, c).toFixed(2),
                   nDiffRows: cells.length, cells: cells.slice(0, 8), strip }; };
        probe = [wx >= 0 ? one(wx, [wy - 5, wy + 1]) : null];
        if (cols.length) probe = probe.concat([one(cols[0]), one(cols[Math.floor(cols.length / 2)]), one(cols[cols.length - 1])]).filter(Boolean);
        // 画布**下沿各行**的透明列数（相位平移后若变化 ⇒ 底边正好压在 y=H 上，AA 随邻边形状漂移）
        const edge = [];
        for (let y = Hm - 3; y < Hm; y++){ let za = 0, zb = 0;
          for (let c = 0; c < P; c++){ const src = (c + S2) % P;
            if (A[(y*P+src)*4+3] === 0) za++;
            if (Bd[(y*P+c)*4+3] === 0) zb++; }
          edge.push([y, za, zb]); }
        probe.push({ bottomEdgeTransparentCols: edge });
      }
      return { only, shift: S2, bad, mag, badCols: cols.length, badRows: rows.length, rowsPerColHist: hist,
               sizes: [t1.cv.width, t1.cv.height, t2.cv.width, t2.cv.height, B.mid.cv.width, B.mid.cv.height],
               nRuns: runs.length, colRuns: runs.slice(0, 30),
               colHead: cols.slice(0, 14), colTail: cols.slice(-14),
               rowHead: rows.slice(0, 10), rowTail: rows.slice(-10), worst, wx, wy, probe };
    };
    // ① 主山体 ② 皴擦 ③ 矾头 ④ 大气罩 —— 哪一味破坏了相位不变性就只在哪一味报坏
    const parts = [0, 1, 2, 3, 4].map(o => diff(o, sh0));
    // **多平移实验**（决定性）：P/4、P/2、3P/4、**P（整周期，应当逐字节全等）**
    const shifty = [1, 2, 3, 4].map(n => { const sh = (P * n) / 4, r = diff(1, sh);
      return { shift: sh, whole: n === 4, bad: r.bad, cols: r.badCols, nRuns: r.nRuns, runs: r.colRuns.slice(0, 6), sizes: r.sizes }; });
    /** A/B：把脊线整体抬高到**永不触及带底**（base+amp < H ⇒ yTop 从不钳位），
     *  再跑同样的多平移。若差异随之消失 ⇒ 缺陷仍与"山脚没入带底/钳位"绑定；
     *  若依旧 ⇒ 与几何无关，是 Skia 对整数平移路径的非不变性（只能换对账口径）。 */
    const noDip = Object.assign({}, D.mid, { baseFrac: 0.30, ampFrac: 0.10 });
    const ab = [1, 2, 3].map(n => { const sh = (P * n) / 4;
      const t1 = E.bakeRidge(noDip, sc, { only: 1 }), t2 = E.bakeRidge(noDip, sc, { shift: sh, only: 1 });
      const Aa = t1.cv.getContext('2d').getImageData(0, 0, P, Hm).data;
      const Bb = t2.cv.getContext('2d').getImageData(0, 0, P, Hm).data;
      let bad = 0, cols = 0, maxY = 0;
      for (const v of t1.ridge) if (v > maxY) maxY = v;
      for (let c = 0; c < P; c++){ const src = (c + sh) % P; let any = false;
        for (let y = 0; y < Hm; y++){ const ia = (y*P+src)*4, ib = (y*P+c)*4;
          for (let k = 0; k < 4; k++) if (Math.abs(Aa[ia+k]-Bb[ib+k])) { bad++; any = true; } }
        if (any) cols++; }
      return { shift: sh, bad, cols, maxRawY: +maxY.toFixed(1), bandH: Hm }; });
    // **数学层**对账（⛔ 不经过栅格化）：先分清"采样几何本身不平移不变"还是"只有画出来不平移不变"
    const r1 = E.bakeRidge(D.mid, sc, { only: 1 }).ridge;
    const r2 = E.bakeRidge(D.mid, sc, { shift: S2, only: 1 }).ridge;
    const st = Math.max(1, Math.round(sc * 2)), off = S2 / st;
    let rfirst = -1, rmax = 0, rmaxi = -1;
    for (let i = 0; i + off < Math.min(r1.length, r2.length); i++){
      const d = Math.abs(r2[i] - r1[i + off]);
      if (rfirst < 0 && d > 1e-9) rfirst = i;
      if (d > rmax){ rmax = d; rmaxi = i; }
    }
    const g = B.glint.ridge, m = B.mid.ridge; let first = -1;
    for (let i = 0; i < Math.max(g.length, m.length); i++) if (g[i] !== m[i]) { first = i; break; }
    return { P, H: Hm, shift: S2, parts, shifty, ab,
             ridgeMath: { len1: r1.length, len2: r2.length, step: st, off, first: rfirst,
                          maxDelta: rmax, maxi: rmaxi,
                          pair: rmaxi < 0 ? null : [r2[rmaxi], r1[rmaxi + off]] },
             glintVsMid: { first, gl: g.length, md: m.length } }; })()`);
  for (const p of out.parts)
    console.log(`only=${p.only} bad=${p.bad} mag1|2-3|4-15|>15=${p.mag.d1}|${p.mag.d2}|${p.mag.d4}|${p.mag.big} cols=${p.badCols} rows=${p.badRows}` +
      ` rows[${p.rowHead[0]}..${p.rowTail[p.rowTail.length - 1]}] worst=${p.worst}@${p.wx},${p.wy}` +
      ` runs=${p.nRuns} ${JSON.stringify(p.colRuns)}`);
  console.log('ridgeMath', JSON.stringify(out.ridgeMath), 'glintVsMid', JSON.stringify(out.glintVsMid));
  for (const s of out.shifty) console.log(`shift=${s.shift}${s.whole ? ' (整周期)' : ''} bad=${s.bad} cols=${s.cols} runs=${s.nRuns} ${JSON.stringify(s.runs)} sizes=${JSON.stringify(s.sizes)}`);
  for (const s of out.ab) console.log(`A/B 无山脚没入: shift=${s.shift} bad=${s.bad} cols=${s.cols} maxRawY=${s.maxRawY} bandH=${s.bandH}`);
  const pr = out.parts.find(p => p.only === 1);
  if (pr && pr.probe) for (const p of pr.probe) console.log(p.c === undefined ? 'probe ' + JSON.stringify(p) :
    `probe c=${p.c} src=${p.src} y1=${p.y1} y2=${p.y2} n=${p.nDiffRows} ${JSON.stringify(p.cells)}${p.strip && p.strip.length ? '\n  strip[y,aA,aB,rA,rB]=' + JSON.stringify(p.strip) : ''}`);
  console.log(JSON.stringify(out, null, 1));
  return out;
}

/* G12 现场诊断：逐档打印每个图层的锚点探针（不透明行分布），用于定位"段数≠1"的成因。 */
async function anchdiag(cdp, tier) {
  await ev(cdp, `(() => { window.__E50.tier = '${tier}'; return true; })()`);
  const r = await ev(cdp, `(() => {
    const E = window.__E50, out = [];
    for (const k of ['far','fog','mid','glint','water','waterHi','motes','near']){
      const p = E.anchorProbe(k);
      const idx = []; p.rows.forEach((v, i) => { if (v) idx.push(i); });
      out.push({ k, sc: p.sc, tileH: p.tileH, y0: +p.y0.toFixed(1), band: +p.band.toFixed(1),
                 runs: p.runs, first: idx[0], last: idx[idx.length - 1], n: idx.length });
    }
    return out; })()`);
  console.log('[' + tier + '] anchorProbe');
  for (const p of r) console.log(JSON.stringify(p));
}

async function main() {
  const cmd = process.argv[2] || 'gates';
  const reused = await reuse();
  const proc = reused ? null : await launch();
  const tgt = await pageTarget();
  const cdp = await CDP.connect(tgt.webSocketDebuggerUrl);
  try {
    await navigateAndBoot(cdp);
    if (cmd === 'gates') await gates(cdp, process.argv[3]);
    else if (cmd === 'shot') await shot(cdp, process.argv[3] || 'HIGH', process.argv[4] || 'shot', parseFloat(process.argv[5] || '10'), process.argv[6]);
    else if (cmd === 'seal') await seal(cdp, process.argv[3] || 'HIGH');
    else if (cmd === 'seamdiag') { await ev(cdp, `(() => { window.__E50.tier = '${process.argv[3] || 'HIGH'}'; return true; })()`); await seamdiag(cdp); }
    else if (cmd === 'anchdiag') await anchdiag(cdp, process.argv[3] || 'LOW');
    else if (cmd === 'tiers') {
      for (const t of TIERS) await shot(cdp, t, `${t.toLowerCase()}_${process.argv[3] || 10}s`, parseFloat(process.argv[3] || '10'), 'music');
      reportDistinct();
      await gates(cdp);
    } else {
      console.log('未知命令：' + cmd + '（可用 gates | shot | seal | tiers）');
    }
  } finally {
    await cdp.send('Browser.close').catch(() => {});
    cdp.ws.close();
    if (!reused && proc) proc.kill();
  }
}
main().catch((e) => { console.error('驱动失败：' + e.message); process.exit(1); });
