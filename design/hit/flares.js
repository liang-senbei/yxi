// design/hit/flares.js —— 点击特效（音符落线、按准那一瞬在判定线上炸开的那一下）× 6：闪光 / 光斑
// 契约：draw(ctx, hit, t, rng)。原点已平移到命中点，x 沿判定线、y 向上为负；hit={u,color,judge,noteH}；
// t∈[0,1] 对应 0.5s；rng 每帧按同一 seed 重开——每款的 rng() 都在开头一次调完、与 t 无关，任一帧单独画也一致；闭包不存状态。
// 节奏统一：env() 在 t≈0.05 冲到最亮，之后指数衰减，t=1 几乎全透明。
(() => {
'use strict';
const TAU = Math.PI * 2;
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const outQ = v => 1 - (1 - v) * (1 - v);
const outC = v => 1 - (1 - v) ** 3;
const inOut = v => v < .5 ? 2 * v * v : 1 - (-2 * v + 2) ** 2 / 2;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
// k>0 向白混，k<0 向黑混
const rgba = (c, a, k = 0) => {
  const f = k > 0 ? v => v + (255 - v) * k : v => v * (1 + k);
  return `rgba(${f(c[0]) | 0},${f(c[1]) | 0},${f(c[2]) | 0},${clamp01(a)})`;
};
// 打击包络：0→a 线性冲到 1，之后 e^{-k(t-a)} 指数衰减
const env = (t, k, a = .05) => t < a ? t / a : Math.exp(-k * (t - a));
// 沿局部 x 轴 ±L、中间亮两头透明的线性渐变
function gradX(ctx, L, c, a, k) {
  const g = ctx.createLinearGradient(-L, 0, L, 0);
  g.addColorStop(0, rgba(c, 0, k)); g.addColorStop(.3, rgba(c, a * .45, k)); g.addColorStop(.5, rgba(c, a, k));
  g.addColorStop(.7, rgba(c, a * .45, k)); g.addColorStop(1, rgba(c, 0, k));
  return g;
}
// 四角星的一条双向臂：沿局部 x 轴 ±L，中心宽 w，边缘内凹（尖端贴着轴线走）
function arm(ctx, L, w) {
  ctx.beginPath();
  ctx.moveTo(0, -w / 2); ctx.quadraticCurveTo(L * .3, -w * .04, L, 0); ctx.quadraticCurveTo(L * .3, w * .04, 0, w / 2);
  ctx.quadraticCurveTo(-L * .3, w * .04, -L, 0); ctx.quadraticCurveTo(-L * .3, -w * .04, 0, -w / 2);
  ctx.closePath(); ctx.fill();
}
// 软光晕：白热心 → 主色 → 透明
function glow(ctx, c, r, a) {
  if (r <= 0 || a <= 0) return;
  const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
  g.addColorStop(0, rgba(c, a, .9)); g.addColorStop(.35, rgba(c, a * .55, .3)); g.addColorStop(1, rgba(c, 0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(0, 0, r, 0, TAU); ctx.fill();
}
const seg = (ctx, x0, y0, x1, y1) => { ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke(); };
const dot = (ctx, x, y, r) => { ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill(); };

window.HITFX = window.HITFX || [];

// ① 十字光：横臂沿判定线拉得长，竖臂短；三层（宽晕 / 体 / 白芯）叠出星芒
window.HITFX.push({
  name: '十字光',
  desc: '横长竖短的十字光芒一闪即收，横臂贴着判定线拉开',
  draw(ctx, hit, t, rng) {
    const { u } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .8;
    const hl = u * (3.3 + .6 * rng()), vl = u * (1.1 + .3 * rng());    // 横 / 竖臂半长
    const e = env(t, 4.2) * J, g = outC(clamp01(t / .28));               // 亮度 / 撑开
    ctx.globalCompositeOperation = 'lighter';
    const layers = [[1, .22, .1], [.4, .55, .5], [.12, .9, 1]];         // [宽/u, alpha, 偏白]
    for (const [L, W, rot] of [[hl * (.5 + .5 * g), 1, 0], [vl * (.6 + .4 * g), .6, Math.PI / 2]]) {
      ctx.save(); ctx.rotate(rot);
      for (const [w, a, k] of layers) { ctx.fillStyle = gradX(ctx, L, c, a * e, k); arm(ctx, L, u * w * W); }
      ctx.restore();
    }
    glow(ctx, c, u * (.7 + .3 * g), e * .6);
  }
});

// ② 拉丝光斑：镜头光斑（anamorphic streak）——极细的横向亮线 + 沿线浮着的鬼影光斑
window.HITFX.push({
  name: '拉丝光斑',
  desc: '镜头光斑式的横向拉丝：一根极细的亮线沿判定线拉开，旁边浮几粒淡淡的鬼影光斑',
  draw(ctx, hit, t, rng) {
    const { u, noteH } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .8;
    const L = u * (3.6 + .6 * rng());
    const ghosts = [];
    for (let i = 0; i < 3; i++) ghosts.push([(rng() < .5 ? -1 : 1) * (.9 + 1.8 * rng()), u * (.18 + .3 * rng()), .5 + .5 * rng()]);   // [x/u, 半径, 亮度]
    const e = env(t, 4) * J, g = outC(clamp01(t / .2)), Lt = L * (.35 + .65 * g);
    ctx.globalCompositeOperation = 'lighter'; ctx.lineCap = 'round';
    for (const [w, a, k] of [[u * .7, .3, .1], [u * .22, .6, .5], [Math.max(1, noteH * .14), .95, 1]]) {
      ctx.strokeStyle = gradX(ctx, Lt, c, a * e, k); ctx.lineWidth = w; seg(ctx, -Lt, 0, Lt, 0);
    }
    for (const [x, r, b] of ghosts) {               // 鬼影随拉丝往外滑一点
      const px = x * u * (.8 + .3 * g);
      ctx.fillStyle = rgba(c, e * .18 * b, .2); dot(ctx, px, 0, r);
      ctx.strokeStyle = rgba(c, e * .4 * b, .6); ctx.lineWidth = 1; ctx.beginPath(); ctx.arc(px, 0, r, 0, TAU); ctx.stroke();
    }
    glow(ctx, c, u * .65, e * .7);
    ctx.fillStyle = rgba(c, e, 1); dot(ctx, 0, 0, Math.max(1.5, u * .1));
  }
});

// ③ 软光球：径向渐变球，0.16 涨到头，然后缩回线上那一点
window.HITFX.push({
  name: '软光球',
  desc: '一团软光球先涨后缩，中心白热、边缘泛主色，缩回线上那一点',
  draw(ctx, hit, t, rng) {
    const { u } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .85;
    const R = u * (2 + .35 * rng()) * J, tp = .16;
    const r = t < tp ? R * outC(t / tp) : R * (1 - .72 * outQ((t - tp) / (1 - tp)));
    const e = env(t, 3.6, .04);
    const g = ctx.createRadialGradient(0, 0, 0, 0, 0, r);
    g.addColorStop(0, rgba(c, e, .95)); g.addColorStop(.25, rgba(c, e * .85, .6)); g.addColorStop(.55, rgba(c, e * .45, .1)); g.addColorStop(1, rgba(c, 0));
    ctx.fillStyle = g; dot(ctx, 0, 0, r);
    const b = clamp01(1 - Math.abs(t - .14) / .3);                       // 涨到头那一瞬向外呼一口很淡的大晕
    if (b > 0) { ctx.globalCompositeOperation = 'lighter'; glow(ctx, c, R * 1.6, b * .12); ctx.globalCompositeOperation = 'source-over'; }
    ctx.fillStyle = rgba(c, env(t, 3), 1); dot(ctx, 0, 0, Math.max(1.5, u * .09));
  }
});

// ④ 快门：四片叶片内缘围成方口，尾巴朝同一方向甩出（风车式咬合），张开→合拢，整体微微拧一下
window.HITFX.push({
  name: '快门',
  desc: '四片叶片像相机快门一样张开再合拢，开口里透出一闪',
  draw(ctx, hit, t, rng) {
    const { u } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .85;
    const phi = (rng() - .5) * .3, dir = rng() < .5 ? 1 : -1;
    const A = u * 1.45 * J, bt = u * .3;                                  // 最大开口半宽 / 叶片厚
    const open = t < .2 ? outC(t / .2) : 1 - inOut(clamp01((t - .28) / .5));
    const a = u * .1 + A * open, e = env(t, 3.6);
    ctx.rotate(phi + dir * .3 * outC(t));
    ctx.globalCompositeOperation = 'lighter';
    ctx.save(); ctx.beginPath(); ctx.rect(-a, -a, 2 * a, 2 * a); ctx.clip(); glow(ctx, c, a * 1.3, env(t, 7) * .9); ctx.restore();   // 开口里透光
    const x0 = -(a + bt + u * .35), x1 = a * .2;
    for (let i = 0; i < 4; i++) {
      const bg = ctx.createLinearGradient(0, -a, 0, -a - bt);             // 叶片：内缘亮、往外淡出
      bg.addColorStop(0, rgba(c, e * .45, .5)); bg.addColorStop(1, rgba(c, e * .06, 0));
      ctx.fillStyle = bg; ctx.fillRect(x0, -a - bt, x1 - x0, bt);
      ctx.strokeStyle = rgba(c, e * .9, .9); ctx.lineWidth = 1.3; seg(ctx, x0, -a, x1, -a);
      ctx.rotate(Math.PI / 2);
    }
  }
});

// ⑤ 亮带：两个光头从命中点沿判定线向两侧跑开，拖尾越跑越长，身后留一条淡淡的亮带
window.HITFX.push({
  name: '亮带',
  desc: '光从命中点沿判定线向两侧跑开，身后留下一段渐渐熄灭的亮带',
  draw(ctx, hit, t, rng) {
    const { u, noteH } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .85;
    const sp = [1 + .14 * (rng() - .5), 1 + .14 * (rng() - .5)];        // 左右速度微差
    const e = env(t, 3.8), run = u * J * (4.1 * outC(clamp01(t / .55)) + 1.2 * Math.max(0, t - .55));
    const X = [-run * sp[0], run * sp[1]];
    ctx.globalCompositeOperation = 'lighter'; ctx.lineCap = 'round';
    const bh = u * .45, bg = ctx.createLinearGradient(0, -bh, 0, bh);       // 留下的亮带：竖向软边，淡，慢慢熄
    bg.addColorStop(0, rgba(c, 0, .3)); bg.addColorStop(.5, rgba(c, env(t, 3.2) * .3, .5)); bg.addColorStop(1, rgba(c, 0, .3));
    ctx.fillStyle = bg; ctx.fillRect(X[0], -bh, X[1] - X[0], 2 * bh);
    for (const s of [0, 1]) {
      const x = X[s], d = s ? 1 : -1, x0 = x - d * u * (1.3 + 1.4 * t);
      const g = ctx.createLinearGradient(x0, 0, x, 0);
      g.addColorStop(0, rgba(c, 0)); g.addColorStop(.7, rgba(c, e * .5, .3)); g.addColorStop(1, rgba(c, e, .9));
      ctx.strokeStyle = g;
      ctx.lineWidth = u * .5; ctx.globalAlpha = .45; seg(ctx, x0, 0, x, 0);
      ctx.lineWidth = Math.max(1.2, noteH * .35); ctx.globalAlpha = 1; seg(ctx, x0, 0, x, 0);
      ctx.fillStyle = rgba(c, e, 1); dot(ctx, x, 0, Math.max(1.5, noteH * .28));
    }
    glow(ctx, c, u * .8, env(t, 8) * .9);                                // 起手那一闪，很快熄
  }
});

// ⑥ 竖闪：一道垂直于判定线的闪光劈下来（±10° 随机偏），落点溅一小道横光；顶端随时间往下缩、越来越细
window.HITFX.push({
  name: '竖闪',
  desc: '一道竖直闪光劈到判定线上（随机偏 ±10°），落点溅出一点横向的光',
  draw(ctx, hit, t, rng) {
    const { u, noteH } = hit, c = hex(hit.color), J = hit.judge === 'perfect' ? 1 : .85;
    const tilt = (rng() - .5) * Math.PI / 9, top = u * (4 + .6 * rng()) * J;
    const e = env(t, 4.5, .04), strike = outC(clamp01(t / .06));
    ctx.globalCompositeOperation = 'lighter';
    const sp = env(t, 9, .06);                                             // 落点溅光：闪得快熄得也快
    ctx.fillStyle = gradX(ctx, u * .9, c, sp * .8, .8); arm(ctx, u * .9, u * .14);
    ctx.rotate(tilt);
    const L = top * (1 - .5 * outQ(t)), yTop = -L, yTip = -top + (top + u * .45) * strike, w = 1 - .5 * t;
    const mid = clamp01(L / (yTip - yTop));                                 // 落点在光柱上的位置
    ctx.lineCap = 'round';
    for (const [lw, a, k] of [[u * .5, .16, .1], [u * .15, .5, .5], [Math.max(1, noteH * .12), .95, 1]]) {
      const g = ctx.createLinearGradient(0, yTop, 0, yTip);
      g.addColorStop(0, rgba(c, 0, k)); g.addColorStop(mid, rgba(c, a * e, k)); g.addColorStop(1, rgba(c, 0, k));
      ctx.strokeStyle = g; ctx.lineWidth = lw * w; seg(ctx, 0, yTop, 0, yTip);
    }
    ctx.fillStyle = rgba(c, e, 1); dot(ctx, 0, 0, Math.max(1.5, u * .09));
  }
});
})();
