// design/swipe/arrows.js —— swipe 音块的左右方向标记 × 5：箭头 / 尖角 / 尾迹
// 契约：draw(ctx, note, t, rng)。note = {x,y,w,h,color,dir,lineY,laneX,laneW}，t∈[0,1) 每 0.8s 一圈，rng 每帧按 seed 重开。
// 五款都先把坐标系挪到音符中心、沿 dir 镜像（scale(dir,1)），下面一律当「方向 = +x」画。
// 方向信息全落在形状上（静止帧就能分左右），动画只是加分；闭包里不存状态。
(() => {
'use strict';
const WH = [255, 255, 255];
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${clamp01(a)})`;
const tint = (c, k) => [c[0] + (255 - c[0]) * k | 0, c[1] + (255 - c[1]) * k | 0, c[2] + (255 - c[2]) * k | 0];   // 往白靠 k
// 环绕的三角脉冲：p 走到 at 时 = 1，半宽 wd
const pulse = (p, at, wd) => Math.max(0, 1 - Math.abs(((p - at) % 1 + 1.5) % 1 - .5) / wd);

function poly(ctx, pts) { ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]); ctx.closePath(); }
function capsule(ctx, x0, x1, hh) {
  ctx.beginPath(); ctx.moveTo(x0 + hh, -hh); ctx.lineTo(x1 - hh, -hh); ctx.arc(x1 - hh, 0, hh, -Math.PI / 2, Math.PI / 2);
  ctx.lineTo(x0 + hh, hh); ctx.arc(x0 + hh, 0, hh, Math.PI / 2, Math.PI * 1.5); ctx.closePath();
}
// 尖角路径：尖端在 x、指向 +x，长 L 高 H（调用方自己 stroke）
function chev(ctx, x, L, H) { ctx.beginPath(); ctx.moveTo(x - L, -H / 2); ctx.lineTo(x, 0); ctx.lineTo(x - L, H / 2); }
// 进入音符局部坐标：原点在中心，+x = 要滑的方向
function local(ctx, n) { ctx.translate(n.x, n.y); ctx.scale(n.dir < 0 ? -1 : 1, 1); ctx.lineCap = ctx.lineJoin = 'round'; }
// 本体一圈很淡的同色光（跟 rhythm-bench 一致），画完本体记得 shadowBlur = 0
function glow(ctx, c) { ctx.shadowColor = rgba(c, .6); ctx.shadowBlur = 8; }

window.SWIPE = window.SWIPE || [];

// ① 三重尖角：本体不动，尾端平口不画角；方向端盖一枚 ⟫⟫ 章——三个尖角嵌套，由近到远渐淡，亮脉冲从块里往外跑
window.SWIPE.push({
  name: '三重尖角',
  desc: '方向端一枚 ⟫⟫ 章：三个尖角嵌套、由近到远渐淡，亮脉冲往外跑；尾端平口',
  draw(ctx, n, t) {
    local(ctx, n); const { w, h } = n, c = hex(n.color);
    glow(ctx, c); capsule(ctx, -w / 2, w / 2, h / 2); ctx.fillStyle = n.color; ctx.fill(); ctx.shadowBlur = 0;
    capsule(ctx, -w / 2 + h * .35, w / 2 - h * .35, h * .22); ctx.fillStyle = rgba(WH, .85); ctx.fill();
    ctx.lineWidth = h * .26;
    const L = h * .8, H = h * 1.7, x0 = w / 2 + h * .25 + L;
    for (let i = 0; i < 3; i++) {
      ctx.strokeStyle = rgba(tint(c, [1, .55, .15][i]), [1, .78, .55][i] + .35 * pulse(t, .2 + i * .17, .18));
      chev(ctx, x0 + i * h * .62, L, H); ctx.stroke();
    }
  },
});

// ② 箭矢：整块就是一支箭——方向端一枚带倒钩的箭镞（比杆高），尾端平口 + 两对斜尾羽；一粒亮点顺着杆往镞上跑
window.SWIPE.push({
  name: '箭矢',
  desc: '整块是一支箭：方向端带倒钩的箭镞、尾端两对尾羽，亮点顺杆往镞跑',
  draw(ctx, n, t) {
    local(ctx, n); const { w, h } = n, c = hex(n.color);
    const xt = w / 2 + h * 1.0, hl = h * 2.0, hh = h * 1.05, nk = h * .6;   // 镞：尖 x / 长 / 半高 / 倒钩深
    const xs1 = xt - hl + nk + h * .3;                                      // 杆插进镞里一点
    glow(ctx, c); ctx.fillStyle = n.color;
    ctx.fillRect(-w / 2, -h / 2, xs1 + w / 2, h);
    poly(ctx, [[xt, 0], [xt - hl, -hh], [xt - hl + nk, 0], [xt - hl, hh]]); ctx.fill(); ctx.shadowBlur = 0;
    // 镞的两条刃口提白
    ctx.lineWidth = h * .2; ctx.strokeStyle = rgba(WH, .9);
    ctx.beginPath(); ctx.moveTo(xt - hl + h * .1, -hh + h * .12); ctx.lineTo(xt - h * .05, 0); ctx.lineTo(xt - hl + h * .1, hh - h * .12); ctx.stroke();
    // 杆芯 + 尾羽（两对，往后斜）
    capsule(ctx, -w / 2 + h * .45, xs1 - h * .2, h * .22); ctx.fillStyle = rgba(WH, .6); ctx.fill();
    ctx.lineWidth = h * .18; ctx.strokeStyle = rgba(WH, .8); ctx.beginPath();
    for (let k = 0; k < 2; k++) {
      const x = -w / 2 + h * .6 + k * h * .8;
      ctx.moveTo(x, -h * .45); ctx.lineTo(x - h * .85, -h * 1.25); ctx.moveTo(x, h * .45); ctx.lineTo(x - h * .85, h * 1.25);
    }
    ctx.stroke();
    // 亮点：从羽跑到镞，两头淡入淡出
    const xa = -w / 2 + h * 1.6, xb = xs1 - h * 1.2, xg = xa + (xb - xa) * t;
    capsule(ctx, xg - h * .8, xg + h * .8, h * .22); ctx.fillStyle = rgba(WH, .95 * Math.sin(Math.PI * t)); ctx.fill();
  },
});

// ③ 彗尾：子弹头在前、身后拖一条渐细渐隐的尾迹（像流星），火星顺着尾巴往后飘
//    尾巴放在方向的反面：亮头领路、尾在身后，是「流星」的通用读法。尾巴要「糊」不要「尖」——
//    渐隐得快、看不到尾尖，否则远看像根针、会把方向读反；鼻子两条刃口提白，静止帧也是个 ›
window.SWIPE.push({
  name: '彗尾',
  desc: '子弹头 + 身后一条渐细渐隐的尾迹，火星往后飘；亮头在前像流星',
  draw(ctx, n, t, rng) {
    local(ctx, n); const { w, h } = n, c = hex(n.color);
    const TL = w * .62, x0 = -w / 2, xn = w / 2 - h * .7, xt = w / 2 + h * .7;   // 尾长 / 尾根 / 鼻根 / 鼻尖
    // 身 + 尾一笔画：尾从整个身高收成一点，但颜色在半路就隐没了（看不见尖）
    const g = ctx.createLinearGradient(x0 - TL, 0, x0 + h * .5, 0);
    g.addColorStop(0, rgba(c, 0)); g.addColorStop(.4, rgba(c, .07)); g.addColorStop(.8, rgba(c, .35)); g.addColorStop(1, n.color);
    glow(ctx, c); ctx.fillStyle = g;
    poly(ctx, [[x0 - TL, 0], [x0, -h / 2], [xn, -h / 2], [xt, 0], [xn, h / 2], [x0, h / 2]]); ctx.fill(); ctx.shadowBlur = 0;
    // 白芯往鼻子方向变宽变亮：热的一头在前
    const gc = ctx.createLinearGradient(x0, 0, xt, 0); gc.addColorStop(0, rgba(WH, .35)); gc.addColorStop(1, rgba(WH, 1));
    ctx.fillStyle = gc;
    poly(ctx, [[x0 + h * .15, -h * .12], [xn - h * .1, -h * .27], [xt - h * .35, 0], [xn - h * .1, h * .27], [x0 + h * .15, h * .12]]); ctx.fill();
    // 鼻子两条刃口提白
    ctx.lineWidth = h * .18; ctx.strokeStyle = rgba(WH, .9);
    ctx.beginPath(); ctx.moveTo(xn, -h / 2 + h * .08); ctx.lineTo(xt - h * .05, 0); ctx.lineTo(xn, h / 2 - h * .08); ctx.stroke();
    // 尾里一道更细的白线，也是半路隐没
    const gt = ctx.createLinearGradient(x0, 0, x0 - TL * .55, 0); gt.addColorStop(0, rgba(WH, .4)); gt.addColorStop(1, rgba(WH, 0));
    ctx.fillStyle = gt; poly(ctx, [[x0, -h * .12], [x0 - TL * .55, 0], [x0, h * .12]]); ctx.fill();
    // 火星：沿尾巴往后飘、散开，越远越小越淡
    for (let i = 0; i < 6; i++) {
      const k = (rng() + t * .5) % 1, y = (rng() - .5) * h * (.3 + k) * 1.1, r = .5 + rng() * 1.1 * (1 - k * .5);
      ctx.fillStyle = rgba(tint(c, .7), (1 - k) * .85);
      ctx.beginPath(); ctx.arc(x0 - h * .2 - k * TL * 1.15, y, r, 0, Math.PI * 2); ctx.fill();
    }
  },
});

// ④ 逐格尖角：本体头端削成铅笔尖；块外排四个越远越小的尖角格，一枚亮角一格一格往外跳，跳到头再从块里出来
window.SWIPE.push({
  name: '逐格尖角',
  desc: '头端削尖，块外四个越远越小的尖角格，一枚亮角一格一格往外跳',
  draw(ctx, n, t) {
    local(ctx, n); const { w, h } = n, c = hex(n.color);
    const xp = w / 2 - h * .55, xt = w / 2 + h * .25, r = h / 2;
    glow(ctx, c); ctx.fillStyle = n.color;
    ctx.beginPath(); ctx.moveTo(-w / 2 + r, -r); ctx.lineTo(xp, -r); ctx.lineTo(xt, 0); ctx.lineTo(xp, r); ctx.lineTo(-w / 2 + r, r);
    ctx.arc(-w / 2 + r, 0, r, Math.PI / 2, Math.PI * 1.5); ctx.closePath(); ctx.fill(); ctx.shadowBlur = 0;
    ctx.fillStyle = rgba(WH, .85);
    poly(ctx, [[-w / 2 + h * .45, -h * .22], [xp - h * .15, -h * .22], [xt - h * .35, 0], [xp - h * .15, h * .22], [-w / 2 + h * .45, h * .22]]); ctx.fill();
    // 四格：k 是这一步亮的那格，上一格留个残影
    const N = 4, k = Math.floor(t * N), f = t * N - k;
    ctx.lineWidth = h * .22;
    for (let i = 0; i < N; i++) {
      const H = h * (1.5 - i * .2), L = H * .5, x = xt + h * .45 + L + i * h * .95;
      ctx.strokeStyle = i === k ? rgba(WH, 1 - .25 * f) : i === k - 1 ? rgba(tint(c, .5), .6 - .3 * f) : rgba(c, .38);
      chev(ctx, x, L, H); ctx.stroke();
    }
  },
});

// ⑤ 箭形块：块本身削成一个 →——头端削尖（微微张开成镞），尾端剪出燕尾口；白芯同形，一道光带朝尖端扫
window.SWIPE.push({
  name: '箭形块',
  desc: '块本身削成箭形：头端削尖、尾端剪出燕尾口，整块就是一个 →；光带朝尖端扫',
  draw(ctx, n, t) {
    local(ctx, n); const { w, h } = n, c = hex(n.color);
    const r = h * .16, hh = h / 2 - r, xL = -w / 2 + r, xR = w / 2 - r, tipL = h * 1.5, nk = h * .55, fl = h * .22;
    const body = [[xL, -hh], [xR - tipL, -hh], [xR - tipL + h * .3, -hh - fl], [xR + h * .2, 0], [xR - tipL + h * .3, hh + fl], [xR - tipL, hh], [xL, hh], [xL + nk, 0]];
    // fill + 同色圆角 stroke：拐角圆一点点，尺寸刚好补回 r
    glow(ctx, c); ctx.fillStyle = ctx.strokeStyle = n.color; ctx.lineWidth = r * 2;
    poly(ctx, body); ctx.fill(); ctx.shadowBlur = 0; ctx.stroke();
    // 白芯同形
    const cc = h * .22, a = xL + h * .55, b = xR - h * .35;
    ctx.fillStyle = rgba(WH, .85);
    poly(ctx, [[a, -cc], [b - tipL * .4, -cc], [b, 0], [b - tipL * .4, cc], [a, cc], [a + nk * .45, 0]]); ctx.fill();
    // 光带：前沿硬、后沿软，从尾扫到尖再消失
    const p = t * 1.3 - .15, xs = -w / 2 + p * (w + h * .4);
    const gs = ctx.createLinearGradient(xs - h * 2.2, 0, xs + h * .5, 0);
    gs.addColorStop(0, rgba(WH, 0)); gs.addColorStop(.82, rgba(WH, .4)); gs.addColorStop(1, rgba(WH, 0));
    poly(ctx, body); ctx.clip(); ctx.fillStyle = gs; ctx.fillRect(xs - h * 2.2, -h, h * 2.7, h * 2);
  },
});
})();
