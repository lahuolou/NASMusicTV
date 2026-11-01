// E50「千里江山」**参考图量色器**（真浏览器解码位图，⛔ 目测取色）。
//
// 为什么需要它：所有者给了《千里江山图》原卷切片当参考图，方案 §三 的取色带是我按需求文档
// 的 HEX 表推的 —— 两者差多少**必须量**，不能"看起来像"。本脚本对**任意两张图**输出同一套指标，
// 直接对比：竖向色带、横向明度节奏、山体包络（冷色首现行）、饱和度分位、高饱和像素色相直方图。
//
// 用法：
//   node docs/archive/verification/scripts/e50_ref_measure.mjs <参考图> [<原型帧>]
//   缺省：参考图 = logs_temp/e50_ref/qianli-jiangshan-ref.jpg
//         原型帧 = output/e50_frames/high_10s.png
//
// ⚠️ **参考图不进仓**（方案裁定 9「⛔ 不引入任何外部位图」）：原卷是公有领域作品，但博物馆扫描件
//    的摄影版权在部分法域仍存争议 ⇒ 图放 gitignored 的 `logs_temp/e50_ref/`，**入仓的只有量出来的数**
//    （`results/e50_ref_measure.txt`）。图没了就请所有者重发一次，⛔ 不要为了"可复跑"把图提交进仓。
//
// ⚠️ 图片经 base64 走 data URL 进页面（⛔ file:// 会被 canvas 跨源污染，getImageData 直接抛）。
import { spawn } from 'node:child_process';
import { readFileSync, existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve, extname } from 'node:path';

const HERE = dirname(process.argv[1]);
const ROOT = resolve(HERE, '..', '..', '..', '..');
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PORT = 9352;                                    // ⛔ 别和 gates(9350)/glyph(9351) 撞
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const REF = resolve(ROOT, process.argv[2] || 'logs_temp/e50_ref/qianli-jiangshan-ref.jpg');
const FRAME = resolve(ROOT, process.argv[3] || 'output/e50_frames/high_10s.png');

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.pending = new Map(); }
  static async connect(url) {
    const ws = new WebSocket(url);
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
    const c = new CDP(ws);
    ws.onmessage = (ev) => { const m = JSON.parse(ev.data);
      if (m.id && c.pending.has(m.id)) { const { resolve: r, reject: j } = c.pending.get(m.id);
        c.pending.delete(m.id); m.error ? j(new Error(m.error.message)) : r(m.result); } };
    return c;
  }
  send(method, params = {}) { return new Promise((res, rej) => { const id = ++this.id;
    this.pending.set(id, { resolve: res, reject: rej }); this.ws.send(JSON.stringify({ id, method, params })); }); }
}

const b64 = (p) => readFileSync(p).toString('base64');
const mime = (p) => (extname(p).toLowerCase() === '.png' ? 'image/png' : 'image/jpeg');

/* 页内测量体：一张图 → 同一组指标。所有阈值都是**相对量**（本图自身的分位），
 * ⛔ 不写"绝对亮度"这种会被曝光骗到的判据（E48 量参考图时的教训）。 */
const MEASURE = `async (src) => {
  const img = new Image(); img.src = src; await img.decode();
  const W = Math.min(1400, img.naturalWidth), H = Math.round(W * img.naturalHeight / img.naturalWidth);
  const c = document.createElement('canvas'); c.width = W; c.height = H;
  const x = c.getContext('2d', { willReadFrequently: true });
  x.drawImage(img, 0, 0, W, H);
  const d = x.getImageData(0, 0, W, H).data;
  const px = (i) => [d[i * 4], d[i * 4 + 1], d[i * 4 + 2]];
  const rgb2hsl = (r, g, b) => { r /= 255; g /= 255; b /= 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
    let h = 0, s = 0; if (mx !== mn) { const dl = mx - mn; s = l > .5 ? dl / (2 - mx - mn) : dl / (mx + mn);
      h = mx === r ? ((g - b) / dl + (g < b ? 6 : 0)) : mx === g ? (b - r) / dl + 2 : (r - g) / dl + 4; h *= 60; }
    return [h, s, l]; };
  const hex = (a) => '#' + a.map(v => Math.round(v).toString(16).padStart(2, '0')).join('');
  const q = (arr, p) => { const a = arr.slice().sort((u, v) => u - v); return a[Math.min(a.length - 1, Math.floor(a.length * p))]; };
  const S = new Float64Array(W * H), L = new Float64Array(W * H), Hu = new Float64Array(W * H);
  for (let i = 0; i < W * H; i++) { const [r, g, b] = px(i); const [h, s, l] = rgb2hsl(r, g, b); S[i] = s; L[i] = l; Hu[i] = h; }
  /* 分位数走**等距抽样**（每 13 像素一个）：⛔ 不要 Math.max(...Array.from(S)) ——
   *    46 万个参数直接爆调用栈（本机实测 RangeError: Maximum call stack size exceeded）。 */
  const sS = [], lS = []; for (let i = 0; i < W * H; i += 13) { sS.push(S[i]); lS.push(L[i]); }
  let sMax = 0; for (let i = 0; i < S.length; i++) if (S[i] > sMax) sMax = S[i];
  /* ① 竖向色带：每 5% 一条，取**该带中位色**（⛔ 均值：会被矿物色峰拉偏） */
  const bands = [];
  for (let k = 0; k < 20; k++) {
    const y0 = Math.floor(H * k / 20), y1 = Math.floor(H * (k + 1) / 20);
    const col = [];
    for (let cx = 0; cx < 24; cx++) { const i = ((y0 + y1) >> 1) * W + Math.floor(W * (cx + .5) / 24); col.push(px(i)); }
    const med = (j) => q(col.map(v => v[j]), .5);
    const m = [med(0), med(1), med(2)], [h, s, l] = rgb2hsl(m[0], m[1], m[2]);
    let ss = 0, n = 0; for (let i = y0 * W; i < y1 * W; i++) { ss += S[i]; n++; }
    bands.push({ y: (k / 20).toFixed(2), hex: hex(m), h: +h.toFixed(0), s: +s.toFixed(3), l: +l.toFixed(3), sMean: +(ss / n).toFixed(3) });
  }
  /* ② 横向明度节奏（24 列的列均值）→ 山体疏密 */
  const cols = [];
  for (let cx = 0; cx < 24; cx++) { let a = 0, n = 0;
    for (let y = 0; y < H; y += 3) { const i = y * W + Math.floor(W * (cx + .5) / 24); a += L[i]; n++; }
    cols.push(+(a / n).toFixed(3)); }
  /* ③ 山体包络：冷色首现行。判据 B−R > 0（绢本暖底 B−R<0，石青/石绿 B−R>0）；
   *    要求**连续 12 行**成立 ⇒ 单根电线/飞鸟不算山。 */
  const env = [];
  for (let cx = 0; cx < 60; cx++) {
    const X = Math.floor(W * (cx + .5) / 60); let run = 0, top = -1;
    for (let y = 0; y < H; y++) { const i = (y * W + X) * 4;
      if (d[i + 2] - d[i] > 0) { if (++run >= 12) { top = y - 11; break; } } else run = 0; }
    env.push(top < 0 ? null : +(top / H).toFixed(3)); }
  const ev = env.filter(v => v !== null);
  /* ④ 高饱和像素的色相直方图（S 取本图 p95 以上）→ 矿物色到底落在哪几个色相 */
  const s95 = q(sS, .95), bins = {};
  let hi = 0; const hiCols = [];
  for (let i = 0; i < W * H; i += 7) if (S[i] >= s95) { const b = Math.round(Hu[i] / 15) * 15;
    bins[b] = (bins[b] || 0) + 1; hi++; if (hiCols.length < 400) hiCols.push(px(i)); }
  const topBins = Object.entries(bins).sort((a, b) => b[1] - a[1]).slice(0, 5)
    .map(([b, n]) => ({ hue: +b, share: +(n / hi).toFixed(3) }));
  const hm = hiCols.map(p => rgb2hsl(p[0], p[1], p[2]));
  const hiMed = [q(hiCols.map(v => v[0]), .5), q(hiCols.map(v => v[1]), .5), q(hiCols.map(v => v[2]), .5)];
  /* ⑤ 角色取色：按 HSL 谓词把像素分成六族，每族出**中位色 + 面积占比 + 竖向落位分位**。
   *    ⇒ 这一节就是新 C 色表的唯一来源（⛔ 不许手抄屏幕取色器，E44 偏差 D24 的成因）。
   *    ⚠️ 谓词本身就是"什么算石青"的定义 ⇒ 改谓词要连结果一起重跑。六族占比之和 <1 是正常的
   *    （过渡色不归属任何一族）。 */
  const ROLES = {
    silk:      [20, 50, .12, .45, null, null],
    ochre:     [15, 45, .45, 1.01, null, null],
    azurite:   [185, 225, .22, 1.01, null, null],
    malachite: [140, 184, .18, 1.01, null, null],
    ink:       [null, null, null, null, null, .24],
    water:     [40, 95, .10, .45, .72, null],
  };
  const roles = {};
  for (const [name, [h0, h1, s0, s1, y0, l1]] of Object.entries(ROLES)) {
    const acc = [], ys = [];
    for (let i = 0; i < W * H; i += 5) {
      const h = Hu[i], s = S[i], l = L[i];
      if (h0 !== null && (h < h0 || h > h1)) continue;
      if (s0 !== null && (s < s0 || s > s1)) continue;
      if (l1 !== null && !(l <= l1)) continue;
      if (y0 !== null && i / (W * H) < y0) continue;
      acc.push(px(i)); ys.push(Math.floor(i / W) / H);
    }
    roles[name] = acc.length < 40
      ? { share: +(acc.length * 5 / (W * H)).toFixed(4), none: true }
      : (() => { const hs = acc.map(p2 => rgb2hsl(p2[0], p2[1], p2[2]));
          /* ⭐ 族内**明度分位色**：中位色只说"这一族平均长什么样"，说不了"这一族能压多暗 /
          *    能提多亮"。近景剪影、矾头高光这两类"最深/最亮"的锚若只能靠中位色 ⇒ 要么画平
          *    （对比不足），要么手编一个"更黑的黑"（= 无出处，D24 重演）。故按族内 L 排序，
          *    取最低 10% / 最高 10% 两片的**中位 RGB**，作为该族的 darkHex / liteHex。 */
          const idx = hs.map((v, j) => j).sort((u, v) => hs[u][2] - hs[v][2]);
          const sliceHex = (a, b) => { const t = idx.slice(a, b);
            return hex([0, 1, 2].map(j => q(t.map(k2 => acc[k2][j]), .5))); };
          const nn = idx.length, n10 = Math.max(1, Math.round(nn * .10));
          return { share: +(acc.length * 5 / (W * H)).toFixed(4),
            darkHex: sliceHex(0, n10), liteHex: sliceHex(nn - n10, nn),
            hex: hex([0, 1, 2].map(j => q(acc.map(v => v[j]), .5))),
            hsl: [+q(hs.map(v => v[0]), .5).toFixed(0), +q(hs.map(v => v[1]), .5).toFixed(3), +q(hs.map(v => v[2]), .5).toFixed(3)],
            y10: +q(ys, .1).toFixed(3), yMed: +q(ys, .5).toFixed(3), y90: +q(ys, .9).toFixed(3) }; })();
  }
  return { W, H, ar: +(W / H).toFixed(2), bands, cols, roles, env: { hit: ev.length / 60, p10: q(ev, .1), p50: q(ev, .5), p90: q(ev, .9) },
    sat: { p50: +q(sS, .5).toFixed(3), p90: +q(sS, .9).toFixed(3),
           p99: +q(sS, .99).toFixed(3), max: +sMax.toFixed(3) },
    lum: { p50: +q(lS, .5).toFixed(3), p05: +q(lS, .05).toFixed(3), p95: +q(lS, .95).toFixed(3) },
    s95: +s95.toFixed(3), topBins, hiMed: { hex: hex(hiMed), s: +q(hm.map(v => v[1]), .5).toFixed(3) } };
}`;

const fmt = (o) => JSON.stringify(o);
function table(title, rows, head) {
  console.log('\n' + title);
  console.log('  ' + head.join(' | '));
  for (const r of rows) console.log('  ' + r.join(' | '));
}

async function main() {
  for (const p of [REF, FRAME]) if (!existsSync(p)) throw new Error('图不存在：' + p);
  const proc = spawn(EDGE, ['--headless=new', '--disable-gpu', '--no-first-run',
    `--user-data-dir=${resolve(ROOT, 'logs_temp/e50_ref_profile')}`, `--remote-debugging-port=${PORT}`,
    '--window-size=400,300', 'about:blank'], { stdio: 'ignore' });
  for (let i = 0; i < 80; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) break; } catch {} await sleep(250); }
  const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json();
  const cdp = await CDP.connect(list.find((t) => t.type === 'page').webSocketDebuggerUrl);
  await cdp.send('Runtime.enable');
  await cdp.send('Page.navigate', { url: 'data:text/html,<title>e50-ref</title>' });
  await sleep(600);
  const ev = async (expr) => { const r = await cdp.send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
    if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails).slice(0, 400)); return r.result.value; };
  const run = async (p) => ev(`(${MEASURE})("data:${mime(p)};base64,${b64(p)}")`);
  const A = await run(REF), B = await run(FRAME);

  console.log('E50 参考图 vs 原型帧 —— 同一套指标（位图降采样到宽 1400，⛔ 目测取色）');
  console.log(`  参考  ${REF.split(/[\\/]/).pop()}  ${A.W}×${A.H}  宽高比 ${A.ar}`);
  console.log(`  原型  ${FRAME.split(/[\\/]/).pop()}  ${B.W}×${B.H}  宽高比 ${B.ar}`);
  table('① 竖向色带（每 5% 一条：带中位色 / H / S / L / 带内平均 S）',
    A.bands.map((a, i) => [a.y, `参考 ${a.hex} h${String(a.h).padStart(3)} s${a.s.toFixed(2)} l${a.l.toFixed(2)} S̄${a.sMean.toFixed(2)}`,
      `原型 ${B.bands[i].hex} h${String(B.bands[i].h).padStart(3)} s${B.bands[i].s.toFixed(2)} l${B.bands[i].l.toFixed(2)} S̄${B.bands[i].sMean.toFixed(2)}`]),
    ['y', '', '']);
  table('② 山体包络（冷色 B−R>0 连续 12 行的首现高度；越小 = 山越高）',
    [['命中列占比', `${(A.env.hit * 100).toFixed(0)}%`, `${(B.env.hit * 100).toFixed(0)}%`],
     ['p10', fmt(A.env.p10), fmt(B.env.p10)], ['p50', fmt(A.env.p50), fmt(B.env.p50)], ['p90', fmt(A.env.p90), fmt(B.env.p90)]],
    ['指标', '参考', '原型']);
  table('③ 饱和度分位（S）与明度分位（L）',
    [['S p50/p90/p99/max', `${A.sat.p50}/${A.sat.p90}/${A.sat.p99}/${A.sat.max}`, `${B.sat.p50}/${B.sat.p90}/${B.sat.p99}/${B.sat.max}`],
     ['L p05/p50/p95', `${A.lum.p05}/${A.lum.p50}/${A.lum.p95}`, `${B.lum.p05}/${B.lum.p50}/${B.lum.p95}`],
     ['p95 以上高饱和像素中位色', `${A.hiMed.hex}（S̄=${A.hiMed.s}）`, `${B.hiMed.hex}（S̄=${B.hiMed.s}）`],
     ['高饱和色相 Top5（15° 分箱）', A.topBins.map(b => `${b.hue}°:${(b.share * 100).toFixed(0)}%`).join(' '),
      B.topBins.map(b => `${b.hue}°:${(b.share * 100).toFixed(0)}%`).join(' ')]],
    ['指标', '参考', '原型']);
  console.log('\n④ 横向明度节奏（24 列均值，看山体疏密）');
  console.log('  参考 ' + fmt(A.cols)); console.log('  原型 ' + fmt(B.cols));
  const ROLE_CN = { silk: '绢本暖底', ochre: '赭石/aged 重暖', azurite: '石青', malachite: '石绿', ink: '墨/皴擦暗部', water: '水面' };
  table('⑤ 角色取色（六族：面积占比 / 中位色 / **族内明度两端** / HSL / 竖向落位 y10·yMed·y90）',
    Object.keys(A.roles).map(k => { const a = A.roles[k], b = B.roles[k];
      const f = (o) => o.none ? `—（${(o.share * 100).toFixed(2)}%）`
        : `${(o.share * 100).toFixed(2)}% ${o.hex} 深${o.darkHex} 亮${o.liteHex} h${o.hsl[0]} s${o.hsl[1].toFixed(2)} l${o.hsl[2].toFixed(2)} y${o.y10}·${o.yMed}·${o.y90}`;
      return [ROLE_CN[k], f(a), f(b)]; }),
    ['族', '参考', '原型']);
  console.log('\n⚠️ 本脚本只出**色差事实**，"像不像千里江山"归所有者判读（方案 §十一）。');
  mkdirSync(resolve(ROOT, 'docs/archive/verification/results'), { recursive: true });
  writeFileSync(resolve(ROOT, 'docs/archive/verification/results/e50_ref_measure.txt'),
    `参考=${REF.split(/[\\/]/).pop()} 原型=${FRAME.split(/[\\/]/).pop()}\n` + JSON.stringify({ ref: A, proto: B }, null, 1));
  console.log('已写入 docs/archive/verification/results/e50_ref_measure.txt');
  await cdp.send('Browser.close').catch(() => {}); cdp.ws.close(); proc.kill();
}
main().catch((e) => { console.error('量色失败：' + e.message); process.exit(1); });
