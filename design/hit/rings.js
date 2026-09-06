// design/hit/rings.js —— 判定线点击特效 × 6：圆环 / 波纹
// 契约：draw(ctx, hit, t, rng)。原点已在命中点，x 沿判定线，y 向上为负；hit={u,color,judge,noteH}；
// t∈[0,1]（宿主 0.5s 播完，t=1 几乎全透明）；rng 每帧按同一 seed 重开——每款的 rng() 都在开头调用、与 t 无关，闭包不存状态。
(() => {
'use strict';
const TAU = Math.PI * 2;
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
// k>0 向白混，k<0 向黑混
const rgba = (c, a, k = 0) => {
  const f = k > 0 ? v => v + (255 - v) * k : v => v * (1 + k);
  return `rgba(${f(c[0]) | 0},${f(c[1]) | 0},${f(c[2]) | 0},${clamp01(a)})`;
};
const snap = t => 1 - Math.exp(-t * 18);             // 0~0.1 弹出六成多
const spread = t => .72 * snap(t) + .28 * t;         // 弹出后匀速慢漂到 1
const soft = t => 1 - (1 - clamp01(t)) ** 2.5;       // 后续波纹用的、缓一点的撑开
const fade = (t, p = 1.6) => (1 - clamp01(t)) ** p;  // t=1 全透明
// 公共量：主色、撑满半径（直径 ≈ 4.6u）、总亮度；good 半径收 15%、亮度收 20%
const base = hit => ({ c: hex(hit.color), R: hit.u * 2.3 * (hit.judge === 'good' ? .85 : 1), A: hit.judge === 'good' ? .8 : 1 });
const ring = (ctx, r) => { ctx.beginPath(); ctx.arc(0, 0, r, 0, TAU); ctx.stroke(); };
const dot = (ctx, x, y, r) => { ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill(); };
// 线上那一点：小圆，t=0 最亮，0.35 内收掉
const core = (ctx, hit, c, A, t) => { if (t < .35) { ctx.fillStyle = rgba(c, A * fade(t / .35, 1.2), .6); dot(ctx, 0, 0, hit.noteH * .4); } };

window.HITFX = window.HITFX || [];

// ① 双环：快环一下子撑到最大、0.45 前就淡光；慢环整段时间慢慢撑开、后淡
window.HITFX.push({
  name: '双环',
  desc: '两个细环一快一慢撑开，快的先淡',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), k = .92 + .16 * rng();
    ctx.lineCap = 'round';
    if (t < .45) {
      const tf = t / .45;
      ctx.lineWidth = 1.2; ctx.strokeStyle = rgba(c, A * fade(tf, 1.3), .55);
      ring(ctx, R * k * spread(tf));
    }
    ctx.lineWidth = 1.8; ctx.strokeStyle = rgba(c, A * .9 * fade(t, 1.4));
    ring(ctx, R * .82 * (.5 * snap(t) + .5 * t));
    core(ctx, hit, c, A, t);
  }
});

// ② 水波纹：石子入水——第一圈弹开，第二、三圈错相跟出，越晚的越淡；入水亮斑迅速缩回去
window.HITFX.push({
  name: '水波纹',
  desc: '三圈同心波纹错相荡开，越晚的越淡',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), j = (rng() - .5) * .04;
    const waves = [[0, 1, spread(t)], [.1 + j, .85, soft((t - .1 - j) / .9)], [.22 - j, .7, soft((t - .22 + j) / .78)]];
    for (let i = 0; i < 3; i++) {
      const [d, m, s] = waves[i]; if (t <= d) continue;
      const tt = (t - d) / (1 - d), a = A * (1 - i * .22) * fade(tt, 1.4) * clamp01(tt * 10);
      ctx.lineWidth = 1.9 - .8 * s; ctx.strokeStyle = rgba(c, a, i ? 0 : .3);
      ring(ctx, R * (.12 + .88 * s * m));
    }
    if (t < .3) { ctx.fillStyle = rgba(c, A * .35 * fade(t / .3, 1.2), .5); dot(ctx, 0, 0, hit.u * .5 * (1 - .8 * snap(t / .3))); }
    core(ctx, hit, c, A, t);
  }
});

// ③ 水面：三道扁半弧只往判定线上方荡开（弧脚落在线上），三颗水珠蹦起又落回线里
window.HITFX.push({
  name: '水面',
  desc: '半圆弧只往线上方荡开，几颗水珠蹦起落回',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), u = hit.u;
    const drops = []; for (let i = 0; i < 3; i++) drops.push([(rng() - .5) * 2.2, .75 + .5 * rng(), rng()]);
    ctx.lineCap = 'round';
    const arcs = [[0, 1, spread(t)], [.09, .82, soft((t - .09) / .91)], [.2, .64, soft((t - .2) / .8)]];
    for (let i = 0; i < 3; i++) {
      const [d, m, s] = arcs[i]; if (t <= d) continue;
      const tt = (t - d) / (1 - d), rx = R * (.1 + .9 * s * m), a = A * (1 - i * .2) * fade(tt, 1.4) * clamp01(tt * 10);
      ctx.lineWidth = 1.9 - .8 * s; ctx.strokeStyle = rgba(c, a, i ? 0 : .3);
      ctx.beginPath(); ctx.ellipse(0, 0, rx, rx * .62, 0, Math.PI, TAU); ctx.stroke();
      ctx.fillStyle = rgba(c, a * .9, .5); dot(ctx, -rx, 0, 1.2); dot(ctx, rx, 0, 1.2);   // 弧脚落在线上的两个亮点
    }
    // 水珠：抛物线蹦起（0.35 到顶），落回线下就不画了
    for (const [dx, v, s] of drops) {
      const v0 = v * 8 * u, yUp = v0 * t - .5 * (v0 / .35) * t * t; if (yUp < 0) continue;
      ctx.fillStyle = rgba(c, A * .9 * fade(t / .7, 1), .5); dot(ctx, dx * u * (.3 + 1.3 * t), -yUp, 1.1 + .8 * s);
    }
    core(ctx, hit, c, A, t);
  }
});

// ④ 虚线环：外圈 16 段虚线顺时针转着撑开，内圈 8 段逆时针跟着；段数不随半径变
window.HITFX.push({
  name: '虚线环',
  desc: '一圈虚线环旋转着撑开，内圈反向慢转',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), a0 = rng() * TAU, rot = .9 * snap(t) + .7 * t;
    ctx.lineCap = 'butt';
    const dashed = (r, n, duty, lw, a, k, ang) => {
      const seg = TAU * r / n;
      ctx.setLineDash([seg * duty, seg * (1 - duty)]); ctx.lineDashOffset = 0;
      ctx.lineWidth = lw; ctx.strokeStyle = rgba(c, a, k);
      ctx.save(); ctx.rotate(ang); ring(ctx, r); ctx.restore();
    };
    dashed(R * spread(t), 16, .55, 1.6, A * fade(t, 1.5), .35, a0 + rot);
    dashed(R * .58 * spread(t), 8, .5, 1.2, A * .7 * fade(t, 1.3), 0, a0 - rot * .7);
    ctx.setLineDash([]);
    core(ctx, hit, c, A, t);
  }
});

// ⑤ 雷达：带缺口的环撑开，缺口领着一根亮针扫一整圈，针后面拖一片淡扇面、环身越远越淡
window.HITFX.push({
  name: '雷达',
  desc: '缺口环撑开，亮针带着淡扇面扫一整圈',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), dir = rng() < .5 ? 1 : -1, a0 = rng() * TAU;
    const r = R * spread(t), th = a0 + dir * TAU * (.25 * snap(t) + .75 * t), gap = 1.1, f = A * fade(t, 1.5);
    ctx.lineCap = 'round'; ctx.lineWidth = 1.5;
    for (const [s0, s1, a] of [[0, 1.2, 1], [1.2, 3.2, .65], [3.2, TAU - gap, .35]]) {
      ctx.strokeStyle = rgba(c, f * a, a === 1 ? .3 : 0);
      ctx.beginPath(); if (dir > 0) ctx.arc(0, 0, r, th - s1, th - s0); else ctx.arc(0, 0, r, th + s0, th + s1); ctx.stroke();
    }
    for (let i = 0; i < 10; i++) {
      const b0 = th - dir * i * .13, b1 = b0 - dir * .14;
      ctx.fillStyle = rgba(c, f * .09 * (1 - i / 10) ** 1.6);
      ctx.beginPath(); ctx.moveTo(0, 0); ctx.arc(0, 0, r, Math.min(b0, b1), Math.max(b0, b1)); ctx.closePath(); ctx.fill();
    }
    ctx.strokeStyle = rgba(c, f, .6); ctx.lineWidth = 1.4;
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(Math.cos(th) * r, Math.sin(th) * r); ctx.stroke();
    core(ctx, hit, c, A, t);
  }
});

// ⑥ 碎环：细环一口气撑到最大、临碎前亮一下变细，碎成一圈小点继续往外散、边散边淡
window.HITFX.push({
  name: '碎环',
  desc: '细环撑到最大时碎成一圈点散开',
  draw(ctx, hit, t, rng) {
    const { c, R, A } = base(hit), u = hit.u, N = 28, TB = .38;
    const P = []; for (let i = 0; i < N; i++) P.push([i * TAU / N + (rng() - .5) * .12, .4 + .5 * rng(), .8 + .6 * rng()]);
    if (t < TB) {
      const s = t / TB, flash = clamp01((s - .7) / .3);
      ctx.lineWidth = 2 - s; ctx.strokeStyle = rgba(c, A, .25 + .6 * flash);
      ring(ctx, R * .92 * spread(t));
    } else {
      const rb = R * .92 * spread(TB), tau = (t - TB) / (1 - TB), s = 1 - (1 - tau) ** 2.2, f = A * fade(tau, 1.3);
      ctx.fillStyle = rgba(c, f, .35);
      for (const [a, v, sz] of P) { const r = rb + v * u * s; dot(ctx, Math.cos(a) * r, Math.sin(a) * r, u * .07 * sz * (1 - .5 * tau)); }
    }
    core(ctx, hit, c, A, t);
  }
});
})();
