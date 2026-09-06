// design/swipe/shape.js —— swipe 音块的左右标记 × 5：本体形状不对称
// 契约：draw(ctx, note, t, rng)。note = {x,y,w,h,color,dir,lineY,laneX,laneW}，t∈[0,1] 循环相位。
// 统一读法：细 / 尖 / 亮的那头 = 滑动方向；圆 / 厚 / 平的那头在后。白芯一律偏向前端，t 只驱动一道顺着方向流的光。
// 所有几何都在「朝右」的局部坐标里画（前端 = +w/2），dir=-1 靠 scale(-1,1) 镜像。
(() => {
'use strict';
const TAU = Math.PI * 2;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const rgba = (c, a) => { const [r, g, b] = hex(c); return `rgba(${r},${g},${b},${a})`; };
// k<0 往黑压，k>0 往白提
const shade = (c, k) => { const [r, g, b] = hex(c), to = k < 0 ? 0 : 255, a = Math.abs(k); return `rgb(${r + (to - r) * a | 0},${g + (to - g) * a | 0},${b + (to - b) * a | 0})`; };
const white = a => `rgba(255,255,255,${a})`;

// 圆角多边形：每个角用 arcTo 倒 r
function rpoly(ctx, pts, r) {
  const n = pts.length;
  ctx.beginPath();
  for (let i = 0; i < n; i++) {
    const [ax, ay] = pts[i], [bx, by] = pts[(i + 1) % n], [cx, cy] = pts[(i + 2) % n];
    if (i === 0) ctx.moveTo((ax + bx) / 2, (ay + by) / 2); else ctx.lineTo((ax + bx) / 2, (ay + by) / 2);
    ctx.arcTo(bx, by, cx, cy, r);
  }
  ctx.closePath();
}
// 胶囊（圆角矩形，r = 半高）
function pill(ctx, x0, x1, hh) {
  ctx.beginPath();
  ctx.moveTo(x0 + hh, -hh); ctx.lineTo(x1 - hh, -hh); ctx.arc(x1 - hh, 0, hh, -Math.PI / 2, Math.PI / 2);
  ctx.lineTo(x0 + hh, hh); ctx.arc(x0 + hh, 0, hh, Math.PI / 2, -Math.PI / 2);
  ctx.closePath();
}
// 水滴：圆头在 bx（半径 r），尖在 tx，两条切线相连
function drop(ctx, bx, r, tx) {
  const L = tx - bx, px = bx + r * r / L, py = r * Math.sqrt(1 - (r * r) / (L * L)), phi = Math.acos(r / L);
  ctx.beginPath();
  ctx.moveTo(tx, 0); ctx.lineTo(px, -py);
  ctx.arc(bx, 0, r, -phi, phi, true);
  ctx.closePath();
}
// 进入局部坐标：原点在音符中心，+x = 滑动方向；同色柔光当底
function enter(ctx, n) {
  ctx.translate(n.x, n.y); ctx.scale(n.dir < 0 ? -1 : 1, 1);
  ctx.shadowColor = rgba(n.color, .7); ctx.shadowBlur = 8;
}
const flat = ctx => { ctx.shadowBlur = 0; };
// 顺着方向流过去的一团光（调用方先 clip 到本体）
function pulse(ctx, x0, x1, r, t, a) {
  const px = x0 + (x1 - x0) * t;
  const g = ctx.createRadialGradient(px, 0, 0, px, 0, r);
  g.addColorStop(0, white(a)); g.addColorStop(1, white(0));
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(px, 0, r, 0, TAU); ctx.fill();
}

window.SWIPE = window.SWIPE || [];

// ① 水滴：圆头在后、整条身子拉成一根尖朝前的针；越远越像一枚倒着的图钉
window.SWIPE.push({
  name: '水滴',
  desc: '圆头在后，身子拉成一根针，针尖指向滑动方向',
  draw(ctx, n, t) {
    const { w, h, color } = n, R = h * .68, bx = -w / 2 + R, tx = w / 2;
    enter(ctx, n);
    drop(ctx, bx, R, tx); ctx.fillStyle = color; ctx.fill();
    flat(ctx);
    ctx.clip();
    drop(ctx, bx, R * .44, tx - h * .9); ctx.fillStyle = white(.85); ctx.fill();
    pulse(ctx, bx, tx - h * .4, h * .55, t, .6);
  }
});

// ② 楔形：直边的梯形，后端厚而平、前端薄成一条刃
window.SWIPE.push({
  name: '楔形',
  desc: '后端厚、前端薄的直边楔子，薄刃朝滑动方向',
  draw(ctx, n, t) {
    const { w, h, color } = n, a = h * .62, b = h * .17, x0 = -w / 2, x1 = w / 2;
    enter(ctx, n);
    rpoly(ctx, [[x0, -a], [x1, -b], [x1, b], [x0, a]], 1.6); ctx.fillStyle = color; ctx.fill();
    flat(ctx);
    ctx.clip();
    rpoly(ctx, [[x0 + h * .55, -a * .44], [x1 - h * .5, -b * .44], [x1 - h * .5, b * .44], [x0 + h * .55, a * .44]], 1); ctx.fillStyle = white(.85); ctx.fill();
    // 一道斜光沿楔子扫向刃口
    const bw = w * .26, sx = x0 - bw + (w + bw) * t;
    const g = ctx.createLinearGradient(sx, 0, sx + bw, 0);
    g.addColorStop(0, white(0)); g.addColorStop(.5, white(.35)); g.addColorStop(1, white(0));
    ctx.fillStyle = g; ctx.fillRect(sx, -a, bw, a * 2);
  }
});

// ③ 子弹：胶囊后端削平（方角、压暗），前端留尖弧的圆头；白芯贴着圆头
window.SWIPE.push({
  name: '子弹',
  desc: '胶囊后端削平压暗，前端圆头，白芯贴着圆头那边',
  draw(ctx, n, t) {
    const { w, h, color } = n, x0 = -w / 2, x1 = w / 2, L = h * 1.25, hh = h / 2;
    enter(ctx, n);
    ctx.beginPath();
    ctx.moveTo(x0, -hh); ctx.lineTo(x1 - L, -hh);
    ctx.bezierCurveTo(x1 - L * .3, -hh, x1 - L * .02, -h * .3, x1, 0);
    ctx.bezierCurveTo(x1 - L * .02, h * .3, x1 - L * .3, hh, x1 - L, hh);
    ctx.lineTo(x0, hh); ctx.closePath();
    const g = ctx.createLinearGradient(x0, 0, x1, 0);
    g.addColorStop(0, shade(color, -.42)); g.addColorStop(.55, color); g.addColorStop(1, color);
    ctx.fillStyle = g; ctx.fill();
    flat(ctx);
    ctx.clip();
    pill(ctx, -w * .1, x1 - h * .5, h * .22); ctx.fillStyle = white(.85); ctx.fill();
    pulse(ctx, -w * .1, x1 - h * .5, h * .5, t, .55);
  }
});

// ④ 斜身：整块向前倾成平行四边形，前上角是锐角，像斜体、像往前冲的人
window.SWIPE.push({
  name: '斜身',
  desc: '整块朝滑动方向倾成平行四边形，前上角是锐角',
  draw(ctx, n, t) {
    const { w, h, color } = n, s = h * 1.1, hh = h / 2, x0 = -w / 2, x1 = w / 2;
    enter(ctx, n);
    rpoly(ctx, [[x0 + s / 2, -hh], [x1 + s / 2, -hh], [x1 - s / 2, hh], [x0 - s / 2, hh]], h * .22); ctx.fillStyle = color; ctx.fill();
    flat(ctx);
    ctx.clip();
    const ch = h * .22, cs = s * .44, c0 = x0 + h * .6, c1 = x1 - h * .5;
    rpoly(ctx, [[c0 + cs / 2, -ch], [c1 + cs / 2, -ch], [c1 - cs / 2, ch], [c0 - cs / 2, ch]], ch); ctx.fillStyle = white(.85); ctx.fill();
    // 一道跟着斜边走的光扫向前端
    const bw = w * .26, sx = x0 - bw + (w + bw + s) * t;
    ctx.transform(1, 0, -s / h, 1, 0, 0);       // 剪切后再画竖条 = 斜条
    const g = ctx.createLinearGradient(sx, 0, sx + bw, 0);
    g.addColorStop(0, white(0)); g.addColorStop(.5, white(.35)); g.addColorStop(1, white(0));
    ctx.fillStyle = g; ctx.fillRect(sx, -h, bw, h * 2);
  }
});

// ⑤ 叉抱：胶囊身子，前端张开成一个 ⊂ 形的叉，叉里抱着一粒白珠
window.SWIPE.push({
  name: '叉抱',
  desc: '前端张成 ⊂ 形的叉抱着一粒白珠，叉口朝滑动方向',
  draw(ctx, n, t) {
    const { w, h, color } = n, Rc = h * .95, th = Math.PI * .3, cx = w / 2 - Rc * Math.cos(th), hh = h / 2;
    enter(ctx, n);
    pill(ctx, -w / 2, cx - Rc + h * .3, hh); ctx.fillStyle = color; ctx.fill();
    ctx.strokeStyle = color; ctx.lineWidth = h * .5; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.arc(cx, 0, Rc, th, -th, false); ctx.stroke();
    flat(ctx);
    ctx.strokeStyle = white(.85); ctx.lineWidth = h * .44;
    ctx.beginPath(); ctx.moveTo(-w / 2 + h * .55, 0); ctx.lineTo(cx - Rc - h * .1, 0); ctx.stroke();
    // 珠子往叉口方向轻轻一送再回来
    const bob = Math.sin(t * TAU) * .15 * h;
    ctx.fillStyle = white(.92); ctx.beginPath(); ctx.arc(cx + h * .1 + bob, 0, h * .3, 0, TAU); ctx.fill();
  }
});
})();
