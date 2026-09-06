// design/hit/frames.js —— 判定线点击特效 × 6：几何框架
// 契约：draw(ctx, hit, t, rng)。原点已平移到命中点，x 沿判定线、y 向上为负；hit = {u, color, judge, noteH}。
// t∈[0,1]（宿主 0.5s 播完，t=1 近乎全透明）；rng 每帧同 seed 重开的确定性随机，这里只拿它给角度加一点微变。
// 统一节奏：pop 在头 0.1 就撑出大半、之后慢漂；fade 线性偏陡。线 1~2px，只用主色的深浅 + 白；撑满 ≤ 6u。
// 每款都留一颗 noteH*.3 的白点在线上到最后（跟现用爆点一样，「线上那一点」）。
(() => {
'use strict';
const TAU = Math.PI * 2;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const rgba = (c, a) => { const [r, g, b] = hex(c); return `rgba(${r},${g},${b},${a})`; };
// k<0 往黑压，k>0 往白提
const shade = (c, k, a = 1) => { const [r, g, b] = hex(c), to = k < 0 ? 0 : 255, m = Math.abs(k); return `rgba(${r + (to - r) * m | 0},${g + (to - g) * m | 0},${b + (to - b) * m | 0},${a})`; };
const white = a => `rgba(255,255,255,${a})`;
const clamp = x => x < 0 ? 0 : x > 1 ? 1 : x;
const pop = t => 1 - Math.pow(1 - t, 8);       // 0.05→.34  0.1→.57  0.25→.90：头 100ms 撑出大半
const snap = t => 1 - Math.pow(1 - t, 14);     // 更陡，0.1 内基本到位：给「一闪」的层用
const fade = t => Math.pow(1 - t, 1.4);
const smooth = x => { x = clamp(x); return x * x * (3 - 2 * x); };
const spring = x => 1 - Math.exp(-6.5 * x) * Math.cos(8 * x);   // 0 → 冲过 1 约 8% → 回落

// 正 n 边形路径：外接圆半径 r，第一个顶点在角 rot
function ngon(ctx, n, r, rot) {
  ctx.beginPath();
  for (let i = 0; i < n; i++) { const a = rot + i * TAU / n; ctx[i ? 'lineTo' : 'moveTo'](r * Math.cos(a), r * Math.sin(a)); }
  ctx.closePath();
}
// 正方形路径（边长 s）：rot=0 时边与坐标轴平行
const square = (ctx, s, rot = 0) => ngon(ctx, 4, s * Math.SQRT1_2, rot + Math.PI / 4);
const dot = (ctx, r, style) => { ctx.fillStyle = style; ctx.beginPath(); ctx.arc(0, 0, r, 0, TAU); ctx.fill(); };
const stroke = (ctx, style, w) => { ctx.strokeStyle = style; ctx.lineWidth = w; ctx.stroke(); };

window.HITFX = window.HITFX || [];

// ① 方框扭菱：Phigros 那款的变体——菱形不是天生 45°，而是和外框对齐着出生、边撑边扭成菱形
window.HITFX.push({
  name: '方框扭菱',
  desc: '两层方框一起撑开，里层边撑边扭 45° 扭成菱形；另有一枚小方框反着缩进中心',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const e = pop(t), f = fade(t), jit = (rng() - .5) * 0.12;
    ctx.lineJoin = 'miter';
    // 中心：一闪即缩的亮盘 + 留到最后的白点
    dot(ctx, u * (0.42 - 0.32 * snap(t)), shade(color, .5, .9 * f));
    dot(ctx, noteH * .3, white(.9 * f));
    // 外层正方框：起手微斜，撑开时摆正
    square(ctx, u * (1.0 + 2.8 * e), jit * (1 - e));
    stroke(ctx, rgba(color, .9 * f), 1.6);
    // 里层：从与外框对齐的正方形扭成 45° 菱形，越扭越大、越淡
    square(ctx, u * (0.7 + 3.0 * e), Math.PI / 4 * e + jit);
    if (judge === 'perfect') { ctx.fillStyle = rgba(color, .08 * f); ctx.fill(); }
    stroke(ctx, rgba(color, .45 * f), 1.2);
    // 反向：一枚小方框缩进中心
    if (judge === 'perfect') { square(ctx, u * (1.6 - 1.45 * e)); stroke(ctx, white(.5 * f), 1); }
  }
});

// ② 六边框：六边形转着撑开，顶点挂白粒；里面三段细弧反向转
window.HITFX.push({
  name: '六边框',
  desc: '六边形框旋转着撑开，六个顶点各挂一粒白点；里面三段细弧反向转',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const e = pop(t), f = fade(t), rot0 = rng() * TAU / 6;
    const R = u * (0.5 + 1.9 * e), rot = rot0 + 0.9 * t;
    // 中心：小实心六边形一闪即缩 + 白点
    ngon(ctx, 6, u * (0.5 - 0.4 * snap(t)), rot); ctx.fillStyle = shade(color, .4, .9 * f); ctx.fill();
    dot(ctx, noteH * .3, white(.9 * f));
    ctx.lineJoin = 'round';
    ngon(ctx, 6, R, rot); stroke(ctx, rgba(color, .9 * f), 1.6);
    if (judge === 'perfect') {
      // 顶点白粒：比框淡得快
      const fp = Math.pow(1 - t, 3);
      ctx.fillStyle = white(.95 * fp);
      for (let i = 0; i < 6; i++) { const a = rot + i * TAU / 6; ctx.beginPath(); ctx.arc(R * Math.cos(a), R * Math.sin(a), 1.8, 0, TAU); ctx.fill(); }
    }
    // 内圈三段细弧反向转
    const r2 = R * 0.6, a0 = -rot0 - 2.4 * t;
    ctx.lineCap = 'round';
    for (let i = 0; i < 3; i++) { const a = a0 + i * TAU / 3; ctx.beginPath(); ctx.arc(0, 0, r2, a, a + 0.9); stroke(ctx, rgba(color, .7 * f), 1.3); }
  }
});

// ③ 角括号：四个 L 形从中心（叠成一个小井字）飞向四角，像取景框撑开
window.HITFX.push({
  name: '角括号',
  desc: '四个角括号从中心飞向四角，臂越飞越短；括号围出的方形亮面一闪就没',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const e = pop(t), f = fade(t), jit = (rng() - .5) * 0.1;
    const d = u * (0.35 + 1.5 * e);          // 括号角点离中心的 x/y 距离（= 半边长）
    const L = u * (0.9 - 0.2 * e);           // 臂长
    ctx.rotate(jit * (1 - e));
    // 头 0.2 内：括号围出的方形亮面
    if (judge === 'perfect' && t < 0.2) { ctx.fillStyle = rgba(color, .18 * (1 - t / 0.2)); ctx.fillRect(-d, -d, 2 * d, 2 * d); }
    // 中心：45° 小方块一闪即缩 + 白点
    square(ctx, u * (0.7 - 0.55 * snap(t)), Math.PI / 4); ctx.fillStyle = shade(color, .5, .9 * f); ctx.fill();
    dot(ctx, noteH * .3, white(.9 * f));
    // 四个括号
    ctx.lineCap = 'butt'; ctx.lineJoin = 'miter';
    for (let i = 0; i < 4; i++) {
      const sx = i & 1 ? -1 : 1, sy = i & 2 ? -1 : 1;
      ctx.beginPath(); ctx.moveTo(sx * (d - L), sy * d); ctx.lineTo(sx * d, sy * d); ctx.lineTo(sx * d, sy * (d - L));
      stroke(ctx, rgba(color, .95 * f), 1.8);
    }
  }
});

// ④ 三角翻转：外层正三角撑开，内层三角绕横轴翻面变倒三角，翻完长到同大、嵌成六芒星
window.HITFX.push({
  name: '三角翻转',
  desc: '正三角撑开，内层三角绕横轴翻个面变成倒三角，翻完正好嵌成六芒星',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const e = pop(t), f = fade(t), rot = -Math.PI / 2 + (rng() - .5) * 0.2 + 0.3 * t;
    const R = u * (0.6 + 1.9 * e);
    // 中心：小实心三角一闪即缩 + 白点
    ngon(ctx, 3, u * (0.55 - 0.42 * snap(t)), rot); ctx.fillStyle = shade(color, .4, .9 * f); ctx.fill();
    dot(ctx, noteH * .3, white(.9 * f));
    ctx.lineJoin = 'miter';
    // 外层正三角
    ngon(ctx, 3, R, rot);
    if (judge === 'perfect') { ctx.fillStyle = rgba(color, .07 * f); ctx.fill(); }
    stroke(ctx, rgba(color, .9 * f), 1.6);
    // 内层：scaleY 从 1 过 0 到 -1 = 绕横轴翻面，翻到一半是一条横线；翻完慢慢长到和外层一样大
    const flip = Math.cos(Math.PI * smooth(t / 0.35)), Ri = R * (0.5 + 0.5 * smooth((t - 0.1) / 0.45));
    ctx.save(); ctx.scale(1, flip);
    ngon(ctx, 3, Ri, rot);
    if (judge === 'perfect') { ctx.fillStyle = rgba(color, .07 * f); ctx.fill(); }
    stroke(ctx, rgba(color, .7 * f), 1.3);
    ctx.restore();
  }
});

// ⑤ 双矩交叉：两条细长矩形从叠成一根竖条起，一左一右转开到 45° 交叉成 ×
window.HITFX.push({
  name: '双矩交叉',
  desc: '两条细长矩形从叠成一根竖条起，一左一右转到 45° 交叉成 ×，越转越长越细，交叠处最亮',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const e = pop(t), f = fade(t), jit = (rng() - .5) * 0.15;
    const a = u * (0.55 + 1.95 * e), b = u * (0.4 - 0.28 * e);     // 半长、半宽
    const th = 0.08 + (Math.PI / 4 - 0.08) * e;                     // 相对竖直的夹角
    ctx.lineJoin = 'miter';
    for (const s of [1, -1]) {
      ctx.save(); ctx.rotate(s * th + jit * (1 - e));
      ctx.beginPath(); ctx.rect(-b, -a, 2 * b, 2 * a);
      if (judge === 'perfect') { ctx.fillStyle = rgba(color, .16 * f); ctx.fill(); }   // 两块叠加，交叠处自然更亮
      stroke(ctx, rgba(color, .85 * f), 1.4);
      ctx.restore();
    }
    // 中心：一闪即缩的白盘 + 白点
    dot(ctx, u * (0.4 - 0.32 * snap(t)), white(.85 * f));
    dot(ctx, noteH * .3, white(.9 * f));
  }
});

// ⑥ 收放框：方框先猛收紧成小方块（头 0.08），再弹开、冲过头回一下；残影慢半拍跟着
window.HITFX.push({
  name: '收放框',
  desc: '方框先猛地收紧成一个小方块，再弹开、冲过头回一下；一层残影慢半拍跟着',
  draw(ctx, { u, color, judge, noteH }, t, rng) {
    const f = fade(t), jit = (rng() - .5) * 0.1, T0 = 0.08;
    const side = tt => tt < T0 ? u * (2.2 - 1.3 * (1 - Math.pow(1 - tt / T0, 2)))     // 2.2u → 0.9u
                               : u * (0.9 + 2.6 * spring((tt - T0) / (1 - T0)));      // 0.9u → 3.5u（冲过头到 ~3.7u）
    // 收紧那一下中心攒亮，弹开时放掉
    const clench = t < T0 ? t / T0 : Math.max(0, 1 - (t - T0) / 0.12);
    dot(ctx, u * 0.45 * (0.6 + 0.4 * clench), shade(color, .5, .9 * clench));
    dot(ctx, noteH * .3, white(.9 * f));
    ctx.lineJoin = 'miter';
    ctx.rotate(jit);
    // 残影：慢 0.06 跟着，淡入
    if (judge === 'perfect' && t > 0.06) { square(ctx, side(t - 0.06)); stroke(ctx, rgba(color, .35 * f * clamp((t - 0.06) / 0.05)), 1); }
    // 主框：收紧时粗、弹开后细
    square(ctx, side(t)); stroke(ctx, rgba(color, .95 * f), t < T0 ? 2.2 : 1.6);
  }
});
})();
