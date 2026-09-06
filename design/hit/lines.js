// design/hit/lines.js —— 点击特效「线条 / 几何」× 6：线自己画出来、再退去
// 契约：draw(ctx, hit, t, rng)。原点已平移到命中点，x 沿判定线、y 向上为负。
// hit = {u, color, judge, noteH}；t∈[0,1] 宿主 0.5s 播完，t=1 几乎透明；rng 每帧按同一 seed 重开，闭包里不存状态。
// 节奏统一：0~0.1 陡 ease-out 出画，之后慢慢退（ease-in）+ 淡出；撑满直径 ≤ 5u；线 1~2px 圆头；不画判定线和音符。
(() => {
'use strict';
const TAU = Math.PI * 2;
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${clamp01(a)})`;
const tint = (c, k) => [c[0] + (255 - c[0]) * k | 0, c[1] + (255 - c[1]) * k | 0, c[2] + (255 - c[2]) * k | 0];   // 往白靠 k
const out = k => 1 - Math.pow(1 - clamp01(k), 3);   // ease-out：出画
const inn = k => Math.pow(clamp01(k), 3);           // ease-in：退去
// 每款开头统一做的事：颜色 + 亮色、淡出曲线、good 小一号、圆头
function setup(ctx, hit, t) {
  const c = hex(hit.color);
  ctx.lineCap = ctx.lineJoin = 'round';
  return { c, hi: tint(c, .55), u: hit.u * (hit.judge === 'good' ? .85 : 1), fade: Math.pow(1 - t, .9) };
}
function line(ctx, x0, y0, x1, y1) { ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke(); }
function dot(ctx, x, y, r) { ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill(); }
function ring(ctx, r, a0 = 0, a1 = TAU) { ctx.beginPath(); ctx.arc(0, 0, r, a0, a1); ctx.stroke(); }

window.HITFX = window.HITFX || [];

// ① 刻度：十二根放射刻度长短相间，从中心一下子长出，再由外向内退回；内圈一道细环串成表盘
window.HITFX.push({
  name: '刻度',
  desc: '十二根放射刻度长短相间，从中心长出，再由外向内退去；内圈一道细环串成表盘',
  draw(ctx, hit, t, rng) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const rot = rng() * TAU / 12;                                   // 整体转个随机小角度，每次命中不一样
    const rIn = u * (.45 + .25 * t);                                // 内端慢慢往外挪
    const grow = out(t / .1), back = 1 - inn((t - .1) / .9);        // 0~0.1 长出，之后慢慢退回
    ctx.lineWidth = 1.5;
    for (let i = 0; i < 12; i++) {
      const a = rot + i * TAU / 12, rOut = rIn + u * (i % 2 ? 1.1 : 1.9) * grow * back;
      if (rOut - rIn < .5) continue;
      ctx.strokeStyle = rgba(i % 2 ? c : hi, (i % 2 ? .6 : .95) * fade);
      line(ctx, Math.cos(a) * rIn, Math.sin(a) * rIn, Math.cos(a) * rOut, Math.sin(a) * rOut);
    }
    ctx.lineWidth = 1; ctx.strokeStyle = rgba(c, .35 * fade);
    ring(ctx, rIn * .8 * grow);
  }
});

// ② 弧环：一段圆弧从随机起点绕中心扫一圈，弧头带亮点；画完之后尾巴追上来，弧自己消失，只剩很淡的轨道
window.HITFX.push({
  name: '弧环',
  desc: '一段圆弧从随机起点绕中心扫一圈、弧头带亮点；画完尾巴追上来，弧自己消失',
  draw(ctx, hit, t, rng) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const a0 = rng() * TAU, R = u * (1.2 + .5 * out(t / .3));
    const head = a0 + TAU * out(t / .35), tail = a0 + TAU * Math.pow(clamp01((t - .08) / .64), 2);   // 尾巴平缓地追，弧一路在扫
    ctx.lineWidth = 1; ctx.strokeStyle = rgba(c, .12 * fade); ring(ctx, R);       // 轨道：很淡
    if (head - tail > .02) { ctx.lineWidth = 1.8; ctx.strokeStyle = rgba(c, .9 * fade); ring(ctx, R, tail, head); }
    const k = 1 - out((t - .3) / .12);                                             // 弧头亮点：画完一圈就熄
    if (k > 0) { ctx.fillStyle = rgba(hi, k * fade); dot(ctx, Math.cos(head) * R, Math.sin(head) * R, 1.5 + u * .06); }
  }
});

// ③ 脉冲：命中点先竖着一跳，两侧各一段透镜形亮包沿判定线跑开，后面跟一道淡回声
window.HITFX.push({
  name: '脉冲',
  desc: '命中点竖着一跳，两侧各一段亮脉冲沿判定线跑开，后面跟一道淡回声',
  draw(ctx, hit, t) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const hh = hit.noteH * .6;
    for (const e of [0, 1]) {                                       // 主脉冲 + 回声
      const tt = t - e * .12; if (tt <= 0) continue;
      const p = u * 2.4 * out(tt / .6), len = u * (.9 - .35 * e) * (1 - inn(tt)), a = (e ? .45 : 1) * fade;
      const x0 = Math.max(0, p - len), x1 = p; if (x1 - x0 < .5) continue;
      for (const s of [-1, 1]) {
        ctx.fillStyle = rgba(c, .7 * a);
        ctx.beginPath(); ctx.moveTo(s * x0, 0); ctx.quadraticCurveTo(s * (x0 + x1) / 2, -hh, s * x1, 0);
        ctx.quadraticCurveTo(s * (x0 + x1) / 2, hh, s * x0, 0); ctx.fill();
        ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(hi, a); line(ctx, s * x0, 0, s * x1, 0);
      }
    }
    const b = u * 1.1 * out(t / .06) * (1 - inn(t / .7));          // 中心竖跳：一下子立起，慢慢缩回
    if (b > .5) { ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(hi, fade); line(ctx, 0, b * .35, 0, -b); }
  }
});

// ④ 收拢：八根短线从四周冲向中心、拖着尾巴聚成一点；点亮一下，再散成一圈发丝环淡去
window.HITFX.push({
  name: '收拢',
  desc: '八根短线从四周冲向中心聚成一点，点亮一下，再散成一圈发丝环淡去',
  draw(ctx, hit, t, rng) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const rot = rng() * TAU;
    ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(hi, .9 * fade);
    for (let i = 0; i < 8; i++) {
      const k = out((t - rng() * .04) / .15);                       // 各自略有先后
      if (k >= 1) continue;
      const a = rot + i * TAU / 8, rIn = u * 1.6 * (1 - k), rOut = rIn + u * .8 * (1 - k * k);
      line(ctx, Math.cos(a) * rIn, Math.sin(a) * rIn, Math.cos(a) * rOut, Math.sin(a) * rOut);
    }
    const p = out((t - .12) / .08);                                 // 聚成的那一点
    if (p > 0) { ctx.fillStyle = rgba(hi, fade); dot(ctx, 0, 0, u * .2 * p * (1 - .5 * t)); }
    const q = out((t - .15) / .5);                                  // 发丝环
    if (q > 0) { ctx.lineWidth = 1; ctx.strokeStyle = rgba(c, .5 * (1 - inn((t - .15) / .6)) * fade); ring(ctx, u * 1.1 * q); }
  }
});

// ⑤ 均衡器：十一根竖细线在判定线上成排立起、中间高两边矮，随后落下；峰顶小横线像 VU 表那样慢半拍
window.HITFX.push({
  name: '均衡器',
  desc: '十一根竖细线成排立起、中间高两边矮，随后落下；峰顶小横线像 VU 表慢半拍',
  draw(ctx, hit, t, rng) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const N = 11, gap = u * .42;
    for (let i = 0; i < N; i++) {
      const j = i - (N - 1) / 2, x = j * gap, env = .3 + .7 * Math.cos(j / (N - 1) * Math.PI);
      const H = u * 1.9 * env * (.7 + .3 * rng()), rise = out((t - Math.abs(j) * .012) / .1);
      const h = H * rise * (1 - inn((t - .15) / .85)), hc = H * rise * (1 - inn((t - .4) / .6));
      if (h < .5) continue;
      ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(c, (.45 + .5 * env) * fade); line(ctx, x, 0, x, -h);
      ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(hi, fade); line(ctx, x - gap * .22, -hc - 2, x + gap * .22, -hc - 2);
    }
  }
});

// ⑥ 分裂：一枚小圆从中心弹出，裂成两枚沿判定线向两侧滑开、边滑边掏空变小，中间牵一根发丝线，随后一起淡去
window.HITFX.push({
  name: '分裂',
  desc: '一枚小圆从中心弹出，裂成两枚沿判定线向两侧滑开、边滑边掏空变小，中间牵一根发丝线',
  draw(ctx, hit, t) {
    const { c, hi, u, fade } = setup(ctx, hit, t);
    const D = u * 2 * out((t - .1) / .55), s = inn((t - .1) / .9);
    const r = u * .34 * out(t / .08) * (1 - .6 * s);
    if (D > 1) { ctx.lineWidth = 1; ctx.strokeStyle = rgba(c, .5 * (1 - inn((t - .1) / .5)) * fade); line(ctx, -D, 0, D, 0); }
    ctx.lineWidth = 1.5; ctx.strokeStyle = rgba(hi, fade); ctx.fillStyle = rgba(hi, fade * (1 - .8 * s));
    for (const x of D > r ? [-D, D] : [0]) {                        // 没分开前就是一枚
      ctx.beginPath(); ctx.arc(x, 0, r, 0, TAU); ctx.stroke();
      dot(ctx, x, 0, r * .55);
    }
    const g = 1 - inn((t - .1) / .6);                               // 中心留一粒余点
    if (D > r && g > 0) { ctx.fillStyle = rgba(c, .7 * g * fade); dot(ctx, 0, 0, u * .09); }
  }
});
})();
