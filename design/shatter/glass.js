// design/shatter/glass.js —— 钢琴块碎裂特效 × 5：玻璃 / 水晶 / 冰
// 契约：draw(ctx, tile, t, rng)。t∈[0,1]，rng 每帧按 seed 重置。
// 每个特效里所有 rng() 都在「几何生成」段调用、与 t 无关，所以任意 t 单独画一帧也一致；闭包里不存状态。
(() => {
'use strict';
const TAU = Math.PI * 2, WH = [255, 255, 255], BK = [0, 0, 0];
const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
const outQ = v => 1 - (1 - v) * (1 - v);
const outC = v => 1 - (1 - v) ** 3;
const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
const mix = (c, k, to) => [c[0] + (to[0] - c[0]) * k | 0, c[1] + (to[1] - c[1]) * k | 0, c[2] + (to[2] - c[2]) * k | 0];
const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${clamp01(a)})`;
const dist = (ax, ay, bx, by) => Math.hypot(ax - bx, ay - by);
function path(ctx, pts) { ctx.beginPath(); ctx.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) ctx.lineTo(pts[i][0], pts[i][1]); ctx.closePath(); }
function centroid(pts) { let x = 0, y = 0; for (const p of pts) { x += p[0]; y += p[1]; } return [x / pts.length, y / pts.length]; }
// 冲击点到方块四角的最远距离
const farCorner = (px, py, x, y, w, h) => Math.max(dist(px, py, x, y), dist(px, py, x + w, y), dist(px, py, x, y + h), dist(px, py, x + w, y + h));
// 从 (px,py) 沿 (dx,dy) 射线打到矩形边界：[hx, hy, 周长参数 s（从左上角起顺时针）, 距离]
function hitRect(px, py, dx, dy, x, y, w, h) {
  const tx = dx > 0 ? (x + w - px) / dx : dx < 0 ? (x - px) / dx : Infinity;
  const ty = dy > 0 ? (y + h - py) / dy : dy < 0 ? (y - py) / dy : Infinity;
  const d = Math.min(tx, ty), hx = px + dx * d, hy = py + dy * d;
  const s = tx < ty ? (dx > 0 ? w + (hy - y) : 2 * w + h + (y + h - hy)) : (dy > 0 ? w + h + (x + w - hx) : hx - x);
  return [hx, hy, s, d];
}

// ① 碎玻璃：径向 + 环状裂纹先铺开，随后整块碎成不规则块，块面随翻转明暗变化（折射高光）
function glass(ctx, tile, t, rng) {
  const { x, y, w, h } = tile, base = hex(tile.color);
  const px = x + w * (.3 + .4 * rng()), py = y + h * (.3 + .4 * rng());
  const n = 9, F = Math.min(w, h) < 70 ? [.3, .62] : [.2, .45, .72], K = F.length, P = 2 * (w + h);
  const sp = [];
  for (let i = 0; i < n; i++) {
    const a = i * TAU / n + (rng() - .5) * .5, dx = Math.cos(a), dy = Math.sin(a);
    const [hx, hy, s, d] = hitRect(px, py, dx, dy, x, y, w, h);
    sp.push({ dx, dy, hx, hy, s, d, g: 1 + .3 * rng(), pts: F.map(f => { const r = d * f * (.85 + .3 * rng()); return [px + dx * r, py + dy * r]; }) });
  }
  const corner = s => s === 0 ? [x, y] : s === w ? [x + w, y] : s === w + h ? [x + w, y + h] : [x, y + h];
  const pieces = [{ pts: sp.map(s => s.pts[0]), ring: 0 }];
  for (let i = 0; i < n; i++) {
    const A = sp[i], B = sp[(i + 1) % n];
    for (let k = 1; k < K; k++) pieces.push({ pts: [A.pts[k - 1], B.pts[k - 1], B.pts[k], A.pts[k]], ring: k });
    let a = A.s, b = B.s; if (b <= a) b += P;   // 最外圈：沿周长把两条辐射线之间的角点补进来
    const cs = [];
    for (const c of [0, w, w + h, 2 * w + h]) { if (c > a && c < b) cs.push(c); if (c + P > a && c + P < b) cs.push(c + P); }
    cs.sort((u, v) => v - u);
    pieces.push({ pts: [A.pts[K - 1], B.pts[K - 1], [B.hx, B.hy], ...cs.map(c => corner(c % P)), [A.hx, A.hy]], ring: K });
  }
  for (const p of pieces) { p.v = .5 + .5 * rng(); p.rot = (rng() - .5) * 3; p.ph = rng() * TAU; p.dl = rng() * .04; }

  const tc = .12;
  ctx.lineJoin = ctx.lineCap = 'round'; ctx.lineWidth = 1;
  if (t < tc) {
    ctx.fillStyle = tile.color; ctx.fillRect(x, y, w, h);
    const g = outC(t / tc);
    ctx.strokeStyle = rgba(WH, .85); ctx.beginPath();
    for (const s of sp) { const r = s.d * Math.min(1, g * s.g); ctx.moveTo(px, py); ctx.lineTo(px + s.dx * r, py + s.dy * r); }
    ctx.stroke();
    for (let k = 0; k < K; k++) {
      const a = clamp01((g * 1.15 - F[k]) / .15); if (!a) continue;
      ctx.strokeStyle = rgba(WH, .7 * a); path(ctx, sp.map(s => s.pts[k])); ctx.stroke();
    }
    ctx.fillStyle = rgba(WH, .9 * (1 - t / tc)); ctx.beginPath(); ctx.arc(px, py, Math.min(w, h) * (.03 + .06 * g), 0, TAU); ctx.fill();
    return;
  }
  const G = .55 * h, S = .32 * Math.max(w, h), facet = clamp01((t - tc) / .1);
  for (const p of pieces) {
    const t0 = tc + p.ring * .045 + p.dl, tau = clamp01((t - t0) / (1 - t0));
    const [cx, cy] = centroid(p.pts), dd = dist(cx, cy, px, py) || 1, ux = (cx - px) / dd, uy = (cy - py) / dd;
    const fl = outC(tau) * S * p.v * (1.2 - .6 * p.ring / K), a = (1 - tau) ** 1.5, rot = p.rot * outQ(tau), sc = 1 - .15 * tau;
    ctx.save();
    ctx.translate(cx + ux * fl, cy + uy * fl + G * tau * tau); ctx.rotate(rot); ctx.scale(sc, sc); ctx.translate(-cx, -cy);
    path(ctx, p.pts);
    const lit = Math.sin(p.ph + rot * 4) * facet;
    ctx.fillStyle = rgba(lit > 0 ? mix(base, .45 * lit * lit, WH) : mix(base, .22 * lit * lit, BK), .95 * a); ctx.fill();
    ctx.strokeStyle = rgba(WH, .45 * a); ctx.stroke();
    ctx.restore();
  }
}

// ② 冰裂：裂纹像冰面一样带霜慢慢蔓延、长出枝杈，蔓延到边后冰片一块块沉落
function ice(ctx, tile, t, rng) {
  const { x, y, w, h } = tile, base = hex(tile.color);
  const px = x + w * (.3 + .4 * rng()), py = y + h * (.3 + .4 * rng());
  const cols = Math.max(2, Math.round(w / 40)), rows = Math.max(2, Math.round(h / 40)), cw = w / cols, ch = h / rows, cell = Math.min(cw, ch);
  const N = [], HM = [], VM = [];   // 抖动网格节点 + 每条格边的中点（边界上的点只沿边界抖）
  for (let r = 0; r <= rows; r++) { N[r] = []; for (let c = 0; c <= cols; c++) {
    const bx = c === 0 || c === cols, by = r === 0 || r === rows;
    N[r][c] = [x + c * cw + (bx ? 0 : (rng() - .5) * .5 * cw), y + r * ch + (by ? 0 : (rng() - .5) * .5 * ch)];
  } }
  for (let r = 0; r <= rows; r++) { HM[r] = []; for (let c = 0; c < cols; c++) {
    const A = N[r][c], B = N[r][c + 1], bd = r === 0 || r === rows;
    HM[r][c] = [(A[0] + B[0]) / 2 + (rng() - .5) * .2 * cw, (A[1] + B[1]) / 2 + (bd ? 0 : (rng() - .5) * .35 * ch)];
  } }
  for (let r = 0; r < rows; r++) { VM[r] = []; for (let c = 0; c <= cols; c++) {
    const A = N[r][c], B = N[r + 1][c], bd = c === 0 || c === cols;
    VM[r][c] = [(A[0] + B[0]) / 2 + (bd ? 0 : (rng() - .5) * .35 * cw), (A[1] + B[1]) / 2 + (rng() - .5) * .2 * ch];
  } }
  const segs = [], br = [];
  const addEdge = (A, M, B, ang) => {
    segs.push([A, M], [M, B]);
    const a = ang + (rng() - .5) * 1.2, l = (.15 + .25 * rng()) * cell;
    br.push([M, [M[0] + Math.cos(a) * l, M[1] + Math.sin(a) * l]]);
  };
  for (let r = 1; r < rows; r++) for (let c = 0; c < cols; c++) addEdge(N[r][c], HM[r][c], N[r][c + 1], rng() < .5 ? -Math.PI / 2 : Math.PI / 2);
  for (let c = 1; c < cols; c++) for (let r = 0; r < rows; r++) addEdge(N[r][c], VM[r][c], N[r + 1][c], rng() < .5 ? 0 : Math.PI);
  const cells = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const pts = [N[r][c], HM[r][c], N[r][c + 1], VM[r][c + 1], N[r + 1][c + 1], HM[r + 1][c], N[r + 1][c], VM[r][c]];
    const [cx, cy] = centroid(pts);
    cells.push({ pts, cx, cy, d: dist(cx, cy, px, py), dl: rng() * .06, rot: (rng() - .5) * .5, dr: rng() - .5 });
  }
  const dust = [];
  for (let i = 0; i < 20; i++) dust.push({ ox: x + rng() * w, oy: y + rng() * h, ts: .45 + rng() * .25, ph: rng() * TAU, r: .6 + rng() * 1.2 });

  const tA = .42, Dm = farCorner(px, py, x, y, w, h), R = outQ(Math.min(1, t / tA)) * Dm * 1.05;
  ctx.lineJoin = ctx.lineCap = 'round';
  const seg = (A, B) => {   // 从离冲击点近的一端向远端生长
    const da = dist(A[0], A[1], px, py), db = dist(B[0], B[1], px, py);
    const [nr, fr, dn, df] = da <= db ? [A, B, da, db] : [B, A, db, da];
    const k = clamp01((R - dn) / (df - dn + 1e-6)); if (!k) return;
    ctx.moveTo(nr[0], nr[1]); ctx.lineTo(nr[0] + (fr[0] - nr[0]) * k, nr[1] + (fr[1] - nr[1]) * k);
  };
  const bseg = (M, E) => {
    const k = clamp01((R - dist(M[0], M[1], px, py)) / (cell * .5)); if (!k) return;
    ctx.moveTo(M[0], M[1]); ctx.lineTo(M[0] + (E[0] - M[0]) * k, M[1] + (E[1] - M[1]) * k);
  };
  if (t < tA) {
    ctx.fillStyle = tile.color; ctx.fillRect(x, y, w, h);
    ctx.save(); ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();   // 霜从冲击点晕开
    ctx.fillStyle = rgba(WH, .1); ctx.beginPath(); ctx.arc(px, py, R, 0, TAU); ctx.fill();
    ctx.fillStyle = rgba(WH, .08); ctx.beginPath(); ctx.arc(px, py, R * .55, 0, TAU); ctx.fill();
    ctx.restore();
    ctx.beginPath();
    for (const [A, B] of segs) seg(A, B);
    for (const [M, E] of br) bseg(M, E);
    ctx.lineWidth = 4; ctx.strokeStyle = rgba(WH, .1); ctx.stroke();
    ctx.lineWidth = 1; ctx.strokeStyle = rgba(WH, .8); ctx.stroke();
    return;
  }
  const fb = clamp01(1 - (t - tA) / .15);   // 枝杈裂纹随崩落淡出
  if (fb) { ctx.beginPath(); for (const [M, E] of br) bseg(M, E); ctx.lineWidth = 1; ctx.strokeStyle = rgba(WH, .8 * fb); ctx.stroke(); }
  ctx.lineWidth = 1;
  for (const c of cells) { const t0 = tA + c.d / Dm * .28 + c.dl; c.tau = clamp01((t - t0) / (1 - t0)); }
  for (const c of [...cells.filter(c => !c.tau), ...cells.filter(c => c.tau)]) {   // 在落的画在前面
    const tau = c.tau, a = 1 - tau * tau;
    ctx.save();
    if (tau) { ctx.translate(c.cx + c.dr * .12 * w * tau, c.cy + .7 * h * tau * tau); ctx.rotate(c.rot * tau); ctx.translate(-c.cx, -c.cy); }
    path(ctx, c.pts);
    ctx.fillStyle = rgba(mix(base, .18 + .2 * tau, WH), .95 * a); ctx.fill();
    ctx.strokeStyle = rgba(WH, .6 * a); ctx.stroke();
    ctx.restore();
  }
  for (const d of dust) {
    const tp = clamp01((t - d.ts) / (1 - d.ts)); if (!tp) continue;
    ctx.fillStyle = rgba(WH, .8 * Math.sin(Math.PI * tp));
    ctx.beginPath(); ctx.arc(d.ox + Math.sin(tp * 5 + d.ph) * cell * .08, d.oy + .5 * h * tp * tp, d.r * cell / 40, 0, TAU); ctx.fill();
  }
}

// ③ 水晶棱柱：方块闪白后收缩消散，细长棱柱从中四散，棱面一亮一暗，伴随星点
function prism(ctx, tile, t, rng) {
  const { x, y, w, h } = tile, base = hex(tile.color), cx = x + w / 2, cy = y + h / 2, M = Math.max(w, h), m = Math.min(w, h);
  const n = Math.max(24, Math.min(60, Math.round(w * h / 500)));
  const sh = [];
  for (let i = 0; i < n; i++) {
    const ox = cx + (rng() - .5) * .8 * w, oy = cy + (rng() - .5) * .8 * h;
    const ang = Math.atan2(oy - cy, ox - cx) + (rng() - .5) * .8;
    const L = Math.max(6, (.1 + .2 * rng()) * m);
    sh.push({ ox, oy, ux: Math.cos(ang), uy: Math.sin(ang), v: (.35 + .65 * rng()) * .5 * M, L, Wd: L * (.22 + .16 * rng()), dl: .08 * rng(), spin: (rng() - .5) * 5, a0: ang + (rng() - .5) * .6, ph: rng() * TAU });
  }
  const sp = [];
  for (let i = 0; i < 36; i++) sp.push({ x: cx + (rng() - .5) * 1.15 * w, y: cy + (rng() - .5) * 1.15 * h, ts: .05 + .5 * rng(), s: (1.5 + 2.5 * rng()) * M / 160 });

  const tk = .3;
  if (t < tk) {   // 本体：闪白 → 缩小消散
    const k = t / tk, s = 1 - .2 * k * k;
    ctx.save(); ctx.translate(cx, cy); ctx.scale(s, s); ctx.translate(-cx, -cy);
    ctx.fillStyle = rgba(mix(base, Math.max(0, 1 - t / .08) * .8, WH), (1 - k) ** 1.2); ctx.fillRect(x, y, w, h);
    ctx.restore();
  }
  const G = .35 * h;
  ctx.lineWidth = .8; ctx.lineJoin = 'round';
  for (const s of sh) {
    const tau = clamp01((t - s.dl) / (1 - s.dl)); if (!tau) continue;
    const a = (1 - tau) ** 1.3, f = outC(tau) * s.v, ang = s.a0 + s.spin * tau, sc = 1 - .3 * tau;
    const L = s.L, Wd = s.Wd, e = L * .22, g = Math.max(0, Math.sin(ang * 2 + s.ph)) ** 6;
    ctx.save(); ctx.translate(s.ox + s.ux * f, s.oy + s.uy * f + G * tau * tau); ctx.rotate(ang); ctx.scale(sc, sc);
    ctx.beginPath(); ctx.moveTo(-L / 2, 0); ctx.lineTo(-L / 2 + e, -Wd / 2); ctx.lineTo(L / 2 - e, -Wd / 2); ctx.lineTo(L / 2, 0); ctx.closePath();
    ctx.fillStyle = rgba(mix(base, .45 + .5 * g, WH), a); ctx.fill();
    ctx.beginPath(); ctx.moveTo(-L / 2, 0); ctx.lineTo(L / 2, 0); ctx.lineTo(L / 2 - e, Wd / 2); ctx.lineTo(-L / 2 + e, Wd / 2); ctx.closePath();
    ctx.fillStyle = rgba(mix(base, .35, BK), a); ctx.fill();
    ctx.strokeStyle = rgba(WH, .7 * a); ctx.beginPath(); ctx.moveTo(-L / 2, 0); ctx.lineTo(L / 2, 0); ctx.stroke();
    ctx.restore();
  }
  for (const p of sp) {
    const k = clamp01((t - p.ts) / .22); if (!k || k === 1) continue;
    const a = Math.sin(Math.PI * k), s = p.s * (.6 + .4 * a), q = s * .28;
    ctx.fillStyle = rgba(WH, .9 * a);
    ctx.beginPath(); ctx.moveTo(p.x, p.y - s); ctx.lineTo(p.x + q, p.y - q); ctx.lineTo(p.x + s, p.y); ctx.lineTo(p.x + q, p.y + q);
    ctx.lineTo(p.x, p.y + s); ctx.lineTo(p.x - q, p.y + q); ctx.lineTo(p.x - s, p.y); ctx.lineTo(p.x - q, p.y - q); ctx.closePath(); ctx.fill();
  }
}

// ④ 薄玻璃：从点击点瞬间炸开，冲击波环扫过，上百片半透明小三角翻着飞，侧面对光时一闪
function thin(ctx, tile, t, rng) {
  const { x, y, w, h } = tile, base = hex(tile.color), M = Math.max(w, h);
  const px = x + w * (.25 + .5 * rng()), py = y + h * (.25 + .5 * rng());
  const cell = Math.max(12, Math.min(24, Math.min(w, h) / 7));
  const cols = Math.max(2, Math.round(w / cell)), rows = Math.max(2, Math.round(h / cell)), cw = w / cols, ch = h / rows;
  const N = [];
  for (let r = 0; r <= rows; r++) { N[r] = []; for (let c = 0; c <= cols; c++) {
    const bx = c === 0 || c === cols, by = r === 0 || r === rows;
    N[r][c] = [x + c * cw + (bx ? 0 : (rng() - .5) * .55 * cw), y + r * ch + (by ? 0 : (rng() - .5) * .55 * ch)];
  } }
  const tris = [], Dm = farCorner(px, py, x, y, w, h);
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
    const a = N[r][c], b = N[r][c + 1], d = N[r + 1][c + 1], e = N[r + 1][c];
    for (const pts of (rng() < .5 ? [[a, b, d], [a, d, e]] : [[a, b, e], [b, d, e]])) {
      const [cx, cy] = centroid(pts);
      tris.push({ pts, cx, cy, dd: dist(cx, cy, px, py), v: .25 + .75 * rng(), rot: (rng() - .5) * 4, w0: 3 + 5 * rng(), ph: rng() * TAU, dl: rng() * .03 });
    }
  }

  const G = .85 * h, S = .55 * M;
  ctx.lineWidth = .8; ctx.lineJoin = 'round';
  for (const q of tris) {
    const t0 = .03 + .1 * q.dd / Dm + q.dl, tau = clamp01((t - t0) / (1 - t0)), a = (1 - tau) ** 1.5;
    ctx.save();
    if (tau) {
      const dd = q.dd || 1, ux = (q.cx - px) / dd, uy = (q.cy - py) / dd, f = outC(tau) * S * q.v * (1.3 - .9 * q.dd / Dm);
      let fl = Math.cos(q.ph + q.w0 * tau); if (Math.abs(fl) < .05) fl = fl < 0 ? -.05 : .05;   // 绕自身轴翻转，侧面时压成一条线
      ctx.translate(q.cx + ux * f, q.cy + uy * f + G * tau * tau); ctx.rotate(q.rot * tau); ctx.scale(1, fl); ctx.translate(-q.cx, -q.cy);
      const g = Math.max(0, Math.sin(q.ph * 1.7 + q.w0 * tau)) ** 12;
      path(ctx, q.pts);
      ctx.fillStyle = rgba(mix(base, g, WH), (.45 + .5 * g) * a); ctx.fill();
      ctx.strokeStyle = rgba(WH, .6 * a); ctx.stroke();
    } else {   // 还没脱离：按原色描边，拼起来就是完整方块
      path(ctx, q.pts); ctx.fillStyle = ctx.strokeStyle = tile.color; ctx.fill(); ctx.stroke();
    }
    ctx.restore();
  }
  if (t < .3) {   // 冲击波环 + 点击闪光，限制在方块内
    const k = t / .3;
    ctx.save(); ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
    ctx.lineWidth = 2 - k; ctx.strokeStyle = rgba(WH, .6 * (1 - k)); ctx.beginPath(); ctx.arc(px, py, outC(k) * Dm * 1.1, 0, TAU); ctx.stroke();
    if (t < .1) { const r = Math.min(w, h) * (.05 + .1 * t / .1), g = ctx.createRadialGradient(px, py, 0, px, py, r); g.addColorStop(0, rgba(WH, .8 * (1 - t / .1))); g.addColorStop(1, rgba(WH, 0)); ctx.fillStyle = g; ctx.beginPath(); ctx.arc(px, py, r, 0, TAU); ctx.fill(); }
    ctx.restore();
  }
}

// ⑤ 冰凌：竖向锯齿裂缝从点击处向两侧蔓延，冰条从点击处起一根根弹起再坠落
function icicle(ctx, tile, t, rng) {
  const { x, y, w, h } = tile, base = hex(tile.color);
  const n = Math.max(4, Math.min(12, Math.round(w / 15))), m = Math.max(2, Math.min(6, Math.round(h / 45))), sw = w / n, sh = h / m;
  const ix = x + w * (.2 + .6 * rng());
  const lines = [];
  for (let j = 0; j <= n; j++) {
    const bd = j === 0 || j === n, pts = [];
    for (let k = 0; k <= m; k++) pts.push([x + j * sw + (bd ? 0 : (rng() - .5) * .6 * sw), y + k * sh + (bd || k === 0 || k === m ? 0 : (rng() - .5) * .6 * sh)]);
    lines.push(pts);
  }
  const strips = [];
  for (let j = 0; j < n; j++) {
    const cx = x + (j + .5) * sw, side = cx < ix ? -1 : 1;
    strips.push({ pts: [...lines[j], ...lines[j + 1].slice().reverse()], cx, cy: y + h / 2, side, dx: Math.abs(cx - ix), rot: side * (.1 + .25 * rng()), dl: rng() * .04, hx: cx - sw * .5 + (.35 + .3 * rng()) * sw });
  }

  const tc = .12, R = outQ(Math.min(1, t / tc)) * w;
  ctx.lineJoin = ctx.lineCap = 'round'; ctx.lineWidth = 1;
  if (t < tc) {
    ctx.fillStyle = tile.color; ctx.fillRect(x, y, w, h);
    for (let j = 1; j < n; j++) {
      const a = clamp01((R - Math.abs(x + j * sw - ix)) / (sw * 1.5)); if (!a) continue;
      const L = lines[j];
      ctx.strokeStyle = rgba(WH, .8 * a); ctx.beginPath(); ctx.moveTo(L[0][0], L[0][1]); for (let k = 1; k <= m; k++) ctx.lineTo(L[k][0], L[k][1]); ctx.stroke();
    }
    return;
  }
  const gr = ctx.createLinearGradient(0, y, 0, y + h);
  gr.addColorStop(0, rgba(mix(base, .4, WH), 1)); gr.addColorStop(1, rgba(mix(base, .08, BK), 1));
  for (const s of strips) {
    const t0 = tc + Math.min(1, s.dx / (w * .5)) * .3 + s.dl, tau = clamp01((t - t0) / (1 - t0));
    ctx.save();
    ctx.globalAlpha = (1 - tau) ** 1.6;
    if (tau) { ctx.translate(s.cx + s.side * .08 * w * tau, s.cy - .4 * h * tau + 1.05 * h * tau * tau); ctx.rotate(s.rot * tau); ctx.translate(-s.cx, -s.cy); }
    path(ctx, s.pts);
    ctx.fillStyle = gr; ctx.fill();
    ctx.strokeStyle = rgba(WH, tau ? .55 : .4); ctx.stroke();
    ctx.strokeStyle = rgba(WH, .22); ctx.beginPath(); ctx.moveTo(s.hx, y + h * .08); ctx.lineTo(s.hx, y + h * .7); ctx.stroke();   // 竖向高光
    ctx.restore();
  }
}

window.SHATTER = window.SHATTER || [];
window.SHATTER.push(
  { name: '碎玻璃', desc: '径向裂纹先铺开，再碎成不规则块，块面翻转时折射高光', draw: (ctx, tile, t, rng) => glass(ctx, tile, clamp01(t), rng) },
  { name: '冰裂', desc: '裂纹像冰面一样带霜慢慢蔓延，长出枝杈，蔓延到边后冰片一块块沉落', draw: (ctx, tile, t, rng) => ice(ctx, tile, clamp01(t), rng) },
  { name: '水晶棱柱', desc: '闪白后收缩消散，细长棱柱四散，棱面一亮一暗，伴随星点', draw: (ctx, tile, t, rng) => prism(ctx, tile, clamp01(t), rng) },
  { name: '薄玻璃', desc: '从点击点瞬间炸开，冲击波扫过，半透明小片翻着飞、侧面对光一闪', draw: (ctx, tile, t, rng) => thin(ctx, tile, clamp01(t), rng) },
  { name: '冰凌', desc: '竖向锯齿裂缝向两侧蔓延，冰条从点击处起一根根弹起坠落', draw: (ctx, tile, t, rng) => icicle(ctx, tile, clamp01(t), rng) },
);
})();
