// 钢琴块碎裂特效 · 风格化 / 二次元音游味 · 5 款
// 契约：draw(ctx, tile, t, rng)  tile={x,y,w,h,color,seed}  t 0..1  rng 每帧按 seed 重置
// 除 window.SHATTER 外不留任何全局量。
(() => {
  const hex = s => [1, 3, 5].map(i => parseInt(s.slice(i, i + 2), 16));
  const mix = (a, b, k) => [0, 1, 2].map(i => Math.round(a[i] + (b[i] - a[i]) * k));
  const rgba = (c, a) => `rgba(${c[0]},${c[1]},${c[2]},${a < 0 ? 0 : a > 1 ? 1 : a})`;
  const W = [255, 255, 255], K = [0, 0, 0], TAU = Math.PI * 2;
  const clamp = u => u < 0 ? 0 : u > 1 ? 1 : u;
  const outExpo = u => u <= 0 ? 0 : u >= 1 ? 1 : 1 - 2 ** (-10 * u);
  const outCubic = u => 1 - (1 - clamp(u)) ** 3;
  const inQuad = u => clamp(u) ** 2;
  const smooth = (a, b, v) => { const u = clamp((v - a) / (b - a)); return u * u * (3 - 2 * u); };

  // 四角星芒：相邻两尖之间用二次曲线，控制点拉向对侧 → 臂细如针
  const star4 = (ctx, cx, cy, lx, ly, rot, k = 0.25) => {
    const co = Math.cos(rot), si = Math.sin(rot);
    const P = (px, py) => [cx + px * co - py * si, cy + px * si + py * co];
    const T = [[0, -ly], [lx, 0], [0, ly], [-lx, 0]];
    let [ax, ay] = T[3];
    ctx.beginPath();
    ctx.moveTo(...P(ax, ay));
    for (const [bx, by] of T) {
      const [qx, qy] = P(-k * (ax + bx), -k * (ay + by));
      ctx.quadraticCurveTo(qx, qy, ...P(bx, by));
      ax = bx; ay = by;
    }
    ctx.fill();
  };

  // 樱花瓣：以原点为中心、尖端朝 -y、尖端带缺口
  const petal = (ctx, L) => {
    const wd = L * 0.62;
    ctx.beginPath();
    ctx.moveTo(0, -L * 0.7);
    ctx.quadraticCurveTo(wd * 0.45, -L * 1.15, wd, -L * 0.45);
    ctx.quadraticCurveTo(wd * 0.95, L * 0.35, 0, L);
    ctx.quadraticCurveTo(-wd * 0.95, L * 0.35, -wd, -L * 0.45);
    ctx.quadraticCurveTo(-wd * 0.45, -L * 1.15, 0, -L * 0.7);
    ctx.fill();
  };

  window.SHATTER = window.SHATTER || [];
  window.SHATTER.push({
    name: '方粒四散',
    desc: 'Phigros 式：小方块四散缩小，两层方框撑开淡出',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const cx = x + w / 2, cy = y + h / 2, m = Math.min(w, h), S = Math.sqrt(w * h);
      const lit = mix(c, W, 0.5);
      // 本体：白闪一下、微放大，0.04 内消失
      if (t < 0.04) {
        const u = t / 0.04, s = 1 + 0.06 * u;
        ctx.fillStyle = rgba(mix(c, W, 0.6), 1 - u);
        ctx.fillRect(cx - w * s / 2, cy - h * s / 2, w * s, h * s);
      }
      // 两层方框撑开：外层随方块长宽比，内层正方
      ctx.lineWidth = 1.5;
      for (let k = 0; k < 2; k++) {
        const u = clamp((t - k * 0.06) / (1 - k * 0.06)), e = outExpo(u);
        const fw = k ? m * (0.4 + 0.45 * e) : w * (0.5 + 0.65 * e), fh = k ? fw : h * (0.5 + 0.65 * e);
        ctx.strokeStyle = rgba(k ? c : lit, 0.9 * (1 - smooth(0.05, 0.6, u)));
        ctx.strokeRect(cx - fw / 2, cy - fh / 2, fw, fh);
      }
      // 方粒：沿贴着方块的椭圆随机方向冲出、边转边缩
      const e = outExpo(t), a = 1 - smooth(0.35, 1, t), rx = w / 2 + m * 0.4, ry = h / 2 + m * 0.4;
      for (let i = 0; i < 32; i++) {
        const ang = rng() * TAU, d = (0.3 + 0.7 * rng()) * e, s0 = S * (0.03 + 0.04 * rng());
        const rot = rng() * TAU + (rng() - 0.5) * 6 * t, tint = rng();
        const s = s0 * (1 - 0.75 * t) / 2;
        const px = cx + Math.cos(ang) * rx * d, py = cy + Math.sin(ang) * ry * d;
        const co = Math.cos(rot) * s, si = Math.sin(rot) * s;
        ctx.fillStyle = rgba(tint < 0.3 ? W : tint < 0.7 ? lit : c, a);
        ctx.beginPath();
        ctx.moveTo(px + co - si, py + si + co);
        ctx.lineTo(px - co - si, py - si + co);
        ctx.lineTo(px - co + si, py - si - co);
        ctx.lineTo(px + co + si, py + si - co);
        ctx.fill();
      }
    }
  });

  window.SHATTER.push({
    name: '樱瓣飘落',
    desc: '方块散成花瓣，旋着飘开、随风落下',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const cx = x + w / 2, cy = y + h / 2, L = Math.sqrt(w * h) * 0.1, drop = Math.max(h, w * 0.6);
      // 本体：本色淡出（0.12 内）
      if (t < 0.12) {
        const u = t / 0.12;
        ctx.fillStyle = rgba(c, (1 - u) ** 1.3);
        ctx.fillRect(x, y, w, h);
      }
      for (let i = 0; i < 30; i++) {
        const px0 = x + w * rng(), py0 = y + h * rng();
        const ang = Math.atan2(py0 - cy, (px0 - cx) * 1.6) + (rng() - 0.5);
        const burst = w * (0.03 + 0.32 * rng()), spin = (rng() - 0.5) * 9, rot0 = rng() * TAU;
        const sway = rng() * TAU, fall = drop * (0.45 + 0.55 * rng()), tint = rng(), delay = rng() * 0.08;
        const tt = clamp((t - delay) / (1 - delay)), e = outExpo(tt);
        const px = px0 + Math.cos(ang) * burst * e + Math.sin(tt * 4 + sway) * w * 0.06 * tt;
        const py = py0 + Math.sin(ang) * burst * e * 0.5 + fall * tt * tt;
        ctx.fillStyle = rgba(mix(c, W, 0.1 + 0.55 * tint), smooth(0, 0.05, tt) * (1 - smooth(0.5, 1, tt)));
        ctx.save();
        ctx.translate(px, py);
        ctx.rotate(rot0 + spin * tt);
        petal(ctx, L * (0.7 + 0.6 * tint) * (1 - 0.3 * tt));
        ctx.restore();
      }
    }
  });

  window.SHATTER.push({
    name: '星芒聚散',
    desc: '方块收成一点，星芒炸开再收拢成一粒光',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const cx = x + w / 2, cy = y + h / 2, m = Math.min(w, h), S = Math.sqrt(w * h);
      const lx = w * 0.25 + m * 0.15, ly = h * 0.4 + m * 0.2, ring = Math.min(lx, ly) * 1.6;
      const lit = mix(c, W, 0.6), fade = 1 - smooth(0.85, 1, t);
      ctx.lineWidth = 1;
      // 本体：0.12 内被吸成一点（ease-out，先快后慢）、微发白
      if (t < 0.12) {
        const u = t / 0.12, s = 1 - outCubic(u);
        ctx.fillStyle = rgba(mix(c, W, u * 0.5), 1);
        ctx.fillRect(cx - w * s / 2, cy - h * s / 2, w * s, h * s);
      }
      // 星芒：0~0.4 撑开，0.4~1 收拢
      const g = t < 0.4 ? outExpo(t / 0.4) : 1 - inQuad((t - 0.4) / 0.6);
      ctx.fillStyle = rgba(W, 0.95 * fade);
      star4(ctx, cx, cy, lx * g, ly * g, 0, 0.3);
      ctx.fillStyle = rgba(c, 0.8 * fade);
      star4(ctx, cx, cy, ring * 0.45 * g, ring * 0.45 * g, Math.PI / 4);
      // 光点：先冲出再回收
      for (let i = 0; i < 12; i++) {
        const ang = rng() * TAU, d = ring * (0.5 + 0.7 * rng()), r = S * (0.01 + 0.015 * rng());
        ctx.fillStyle = rgba(rng() < 0.5 ? W : lit, 0.9 * fade);
        ctx.beginPath();
        ctx.arc(cx + Math.cos(ang) * d * g, cy + Math.sin(ang) * d * g, r * (0.5 + 0.5 * g) + 0.3, 0, TAU);
        ctx.fill();
      }
      // 细环：0~0.5 扩散淡出
      if (t < 0.5) {
        const u = t / 0.5;
        ctx.strokeStyle = rgba(lit, 0.7 * (1 - u));
        ctx.beginPath();
        ctx.arc(cx, cy, ring * outExpo(u), 0, TAU);
        ctx.stroke();
      }
      // 核心光点：0.25 后浮现，0.6 最亮，1 消失
      const core = S * 0.04 * smooth(0.25, 0.55, t) * (1 - smooth(0.6, 1, t));
      if (core > 0.2) {
        ctx.fillStyle = rgba(W, 1);
        ctx.beginPath();
        ctx.arc(cx, cy, core, 0, TAU);
        ctx.fill();
      }
    }
  });

  window.SHATTER.push({
    name: '速度线',
    desc: '漫画集中线炸开，方块被压成一道细线闪没',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const cx = x + w / 2, cy = y + h / 2, S = Math.sqrt(w * h);
      const lit = mix(c, W, 0.5);
      // 本体：0~0.3 竖向压扁、横向微胀；0.3~0.55 横向收拢淡出
      if (t < 0.55) {
        const u1 = smooth(0, 0.3, t), u2 = inQuad((t - 0.3) / 0.25);
        const bw = w * (1 + 0.2 * u1) * (1 - u2), bh = Math.max(h * (1 - 0.96 * u1), 2);
        ctx.fillStyle = rgba(mix(c, W, 0.2 + 0.8 * u1), 1 - u2);
        ctx.fillRect(cx - bw / 2, cy - bh / 2, bw, bh);
        if (t < 0.08) {
          ctx.fillStyle = rgba(W, 0.8 * (1 - t / 0.08));
          ctx.fillRect(cx - bw / 2, cy - bh / 2, bw, bh);
        }
      }
      // 集中线：从方块边缘外射出，尖端朝外；0.12 抽满，之后从根部退去
      const recede = smooth(0.25, 0.75, t), a = 0.9 * (1 - smooth(0.4, 0.8, t));
      for (let i = 0; i < 36; i++) {
        const ang = rng() * TAU, len = S * (0.15 + 0.32 * rng()), wd = 1 + 2 * rng();
        const gap = S * (0.04 + 0.12 * rng()), delay = rng() * 0.06, tint = rng();
        const dx = Math.cos(ang), dy = Math.sin(ang);
        const edge = Math.min(Math.abs(dx) > 1e-6 ? w / 2 / Math.abs(dx) : 1e9, Math.abs(dy) > 1e-6 ? h / 2 / Math.abs(dy) : 1e9);
        const r0 = edge + gap + len * recede, r1 = edge + gap + len * outExpo((t - delay) / 0.12);
        if (r1 <= r0) continue;
        const nx = -dy * wd / 2, ny = dx * wd / 2;
        ctx.fillStyle = rgba(tint < 0.4 ? W : lit, a);
        ctx.beginPath();
        ctx.moveTo(cx + dx * r0 + nx, cy + dy * r0 + ny);
        ctx.lineTo(cx + dx * r0 - nx, cy + dy * r0 - ny);
        ctx.lineTo(cx + dx * r1, cy + dy * r1);
        ctx.fill();
      }
    }
  });

  window.SHATTER.push({
    name: '切片错位',
    desc: '方块拆成横向细条，抖动错位后左右滑出',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const n = Math.max(6, Math.min(16, Math.round(h / 12))), sh = h / n;
      const dark = mix(c, K, 0.35), lit = mix(c, W, 0.55);
      const frame = Math.floor(t * 36), fade = 1 - smooth(0.6, 1, t);
      for (let i = 0; i < n; i++) {
        const dir = rng() < 0.5 ? -1 : 1, delay = rng() * 0.25, dist = w * (0.35 + 0.55 * rng());
        const shade = rng(), jit = Math.floor(rng() * 97);
        const u = clamp((t - delay) / 0.45), e = outCubic(u);
        // 滑出前：按帧量化的左右抖动
        const jitter = u > 0 ? 0 : (((frame * 7 + i * 13 + jit) % 5) - 2) * w * 0.02;
        const hh = sh * (1 - 0.5 * e), oy = y + i * sh + (sh - hh) / 2;
        const ox = x + jitter + dir * dist * e, a = (1 - 0.85 * e) * fade;
        // 残影：拖在身后，速度越快越长
        const trail = dir * w * 0.12 * (1 - u) ** 2 * (u > 0 ? 1 : 0);
        if (trail) {
          ctx.fillStyle = rgba(lit, 0.5 * a);
          ctx.fillRect(ox - trail, oy, w, hh);
        }
        ctx.fillStyle = rgba(shade < 0.25 ? dark : c, a);
        ctx.fillRect(ox, oy, w, hh);
      }
      // 扫描线闪烁（0.2 内）
      if (t < 0.2) {
        ctx.fillStyle = rgba(W, 0.6 * (1 - t / 0.2));
        for (let k = 0; k < 2; k++) {
          const row = (frame * 31 + k * 17) % n;
          ctx.fillRect(x - w * 0.05, y + row * sh, w * 1.1, 1);
        }
      }
    }
  });
})();
