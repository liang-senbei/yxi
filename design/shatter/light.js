// 钢琴块碎裂特效 · 光 / 能量 / 数字化 · 5 个
// 契约：draw(ctx, tile, t, rng)，tile={x,y,w,h,color,seed}，t∈[0,1]，rng 每帧同序列，函数内不存状态。
(() => {
  const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  // k>0 向白混，k<0 向黑混
  const rgba = (c, a, k = 0) => {
    const f = k > 0 ? v => v + (255 - v) * k : v => v * (1 + k);
    return `rgba(${f(c[0]) | 0},${f(c[1]) | 0},${f(c[2]) | 0},${a < 0 ? 0 : a > 1 ? 1 : a})`;
  };
  const clamp01 = v => v < 0 ? 0 : v > 1 ? 1 : v;
  const outQuad = x => 1 - (1 - x) * (1 - x);
  const outCubic = x => 1 - (1 - x) ** 3;
  const TAU = Math.PI * 2;
  // 起手一闪：0 → 峰值(0.06) → 0.22 熄
  const flash = t => t < 0.06 ? t / 0.06 : clamp01(1 - (t - 0.06) / 0.16);
  // 三层描线仿霓虹辉光（比 shadowBlur 便宜得多）
  const glowStroke = (ctx, c, a, lw) => {
    ctx.strokeStyle = rgba(c, a * 0.28); ctx.lineWidth = lw * 4; ctx.stroke();
    ctx.strokeStyle = rgba(c, a * 0.7, 0.35); ctx.lineWidth = lw * 1.4; ctx.stroke();
    ctx.strokeStyle = rgba(c, a, 0.95); ctx.lineWidth = lw * 0.6; ctx.stroke();
  };

  window.SHATTER = window.SHATTER || [];

  // 1 ─────────────────────────────────────────────── 溶解成光
  window.SHATTER.push({
    name: '溶解成光',
    desc: '溶解前沿自上而下扫过，方块化作光点缓缓上浮',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color), s = Math.min(w, h) / 100;
      const N = 180, P = [];
      for (let i = 0; i < N; i++) P.push([rng(), rng(), rng(), rng(), rng(), rng()]);
      const F = tt => outQuad(clamp01(tt / 0.85)); // 前沿走过的高度比例
      const f = flash(t), top = y + F(t) * h;
      if (top < y + h) {
        ctx.fillStyle = rgba(c, 1 - t * t * 0.4, f * 0.6);
        ctx.fillRect(x, top, w, y + h - top);
        // 前沿亮边
        const gh = Math.min(22, h * 0.3, y + h - top);
        const g = ctx.createLinearGradient(0, top, 0, top + gh);
        g.addColorStop(0, rgba(c, 0.9 * (1 - t), 0.95));
        g.addColorStop(1, rgba(c, 0, 0.95));
        ctx.fillStyle = g;
        ctx.fillRect(x, top, w, gh);
      }
      ctx.globalCompositeOperation = 'lighter';
      for (const [u, v, sp, sz, wh, dr] of P) {
        const tb = u * 0.8; // 出生 = 前沿经过之时
        if (t < tb) continue;
        const age = (t - tb) / (1 - tb);
        const e = outCubic(age);
        const px = x + v * w + (dr - 0.5) * 14 * s * e;
        const py = y + F(tb) * h - (0.25 + sp * 0.75) * h * 0.45 * e;
        const a = (1 - age) ** 1.5;
        if (a < 0.02) continue;
        ctx.fillStyle = rgba(c, a, 0.15 + (0.3 + wh * 0.6) * (1 - age)); // 初生白热，渐冷回本色
        ctx.beginPath(); ctx.arc(px, py, (0.5 + sz * sz * 1.6) * s, 0, TAU); ctx.fill();
      }
    }
  });

  // 2 ─────────────────────────────────────────────── 像素熄灭
  window.SHATTER.push({
    name: '像素熄灭',
    desc: '方块像素化，格子按随机次序闪白后一个个熄灭',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color);
      const cell = Math.max(8, Math.min(w, h) / 8);
      const cols = Math.max(3, Math.round(w / cell)), rows = Math.max(2, Math.round(h / cell));
      const cw = w / cols, ch = h / rows;
      const gap = Math.max(1, Math.min(cw, ch) * 0.14 * clamp01(t / 0.12)); // 间隙张开 = 像素化显形
      const f = flash(t);
      for (let j = 0; j < rows; j++) for (let i = 0; i < cols; i++) {
        const r1 = rng(), r2 = rng();
        // 熄灭时刻：随机为主，略带由中心向外
        const d = Math.hypot((i + 0.5) / cols - 0.5, (j + 0.5) / rows - 0.5) * 1.4;
        const td = 0.1 + 0.68 * clamp01(0.75 * r1 + 0.25 * d);
        const age = (t - td) / 0.12;
        if (age >= 1) continue;
        let a = 1, k = f * 0.55, sc = 1;
        if (age > 0) {          // 闪白 → 缩小消失
          k = 1 - age * 0.4; a = 1 - age * age; sc = 1 - age * 0.6;
        } else if (age > -0.5) { // 临熄前先提亮预警
          k = Math.max(k, 0.5 * r2 * (age + 0.5) * 2);
        }
        const sw = (cw - gap) * sc, sh = (ch - gap) * sc;
        ctx.fillStyle = rgba(c, a, k);
        ctx.fillRect(x + i * cw + (cw - sw) / 2, y + j * ch + (ch - sh) / 2, sw, sh);
      }
    }
  });

  // 3 ─────────────────────────────────────────────── 扫描消散
  window.SHATTER.push({
    name: '扫描消散',
    desc: '一道扫描线自上而下掠过，扫过的行撕成光带向两侧散去',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color), s = Math.min(w, h) / 100;
      const rowH = Math.max(3, h / 36), rows = Math.ceil(h / rowH);
      const R = [];
      for (let j = 0; j < rows; j++) R.push([rng(), rng(), rng()]);
      const N = 70, P = [];
      for (let i = 0; i < N; i++) P.push([rng(), rng(), rng(), rng()]);
      const F = tt => outQuad(clamp01(tt / 0.8)); // 扫描线进度
      const f = flash(t), ly = y + F(t) * h;
      // 下方完整本体
      if (ly < y + h) {
        ctx.fillStyle = rgba(c, 1, f * 0.6);
        ctx.fillRect(x, ly, w, y + h - ly);
      }
      // 上方各行：被扫过后横向撕开、变淡
      for (let j = 0; j < rows; j++) {
        const [r1, r2, r3] = R[j];
        const q = Math.min((j + 0.5) / rows, 0.999);
        const tp = 0.8 * (1 - Math.sqrt(1 - q)); // F(tp) = q
        if (t < tp) continue;
        const age = clamp01((t - tp) / Math.min(0.42, 1 - tp));
        if (age >= 1) continue;
        const e = outCubic(age), ry = y + j * rowH, rh = Math.max(1, rowH - 1);
        const split = w * (0.2 + r1 * 0.6), slide = e * w * (0.15 + r2 * 0.25), k = 1 - e * (0.5 + r3 * 0.3);
        ctx.fillStyle = rgba(c, (1 - age) ** 1.6, 0.35 + (1 - age) * 0.5);
        const lw = split * k, rw = (w - split) * k; // 两半各自缩短，裂口从撕开点张大
        ctx.fillRect(x + split - lw - slide, ry, lw, rh);
        ctx.fillRect(x + split + slide, ry, rw, rh);
      }
      // 扫描线 + 上沿光晕
      const la = 1 - t ** 3;
      const gh = Math.min(16, h * 0.25, ly - y);
      const g = ctx.createLinearGradient(0, ly - gh, 0, ly);
      g.addColorStop(0, rgba(c, 0, 0.8));
      g.addColorStop(1, rgba(c, 0.55 * la, 0.8));
      ctx.fillStyle = g;
      ctx.fillRect(x, ly - gh, w, gh);
      ctx.fillStyle = rgba(c, la, 1);
      ctx.fillRect(x, ly - 1, w, 2);
      // 扫过时溅起的小光粒
      ctx.globalCompositeOperation = 'lighter';
      for (const [u, v, sp, sz] of P) {
        const tb = u * 0.75;
        if (t < tb) continue;
        const age = (t - tb) / (1 - tb), e = outCubic(age);
        const a = (1 - age) ** 2;
        if (a < 0.02) continue;
        const px = x + v * w, py = y + F(tb) * h - (0.1 + sp * 0.3) * h * e;
        const r = (0.4 + sz * sz * 0.9) * s;
        ctx.fillStyle = rgba(c, a, 0.7);
        ctx.fillRect(px - r, py - r, r * 2, r * 2);
      }
    }
  });

  // 4 ─────────────────────────────────────────────── 霓虹裂开
  window.SHATTER.push({
    name: '霓虹裂开',
    desc: '本体暗下只剩霓虹描边，裂纹自点击处炸开，描边断成数截散逸熄灭',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color), s = Math.min(w, h) / 100;
      const cx = x + w * (0.3 + rng() * 0.4), cy = y + h * (0.3 + rng() * 0.4);
      // 裂纹：6 条射线，各 3 折，末点落在边框上
      const NC = 6, cracks = [];
      for (let i = 0; i < NC; i++) {
        const th = ((i + 0.2 + rng() * 0.6) / NC) * TAU, dx = Math.cos(th), dy = Math.sin(th);
        // 射线到矩形边的距离
        const D = Math.min(dx > 0 ? (x + w - cx) / dx : dx < 0 ? (x - cx) / dx : 1e9,
                           dy > 0 ? (y + h - cy) / dy : dy < 0 ? (y - cy) / dy : 1e9);
        const pts = [[cx, cy]];
        for (let k = 1; k <= 3; k++) {
          const l = D * k / 3, jt = k < 3 ? (rng() - 0.5) * 0.18 * Math.min(w, h) : 0;
          pts.push([cx + dx * l - dy * jt, cy + dy * l + dx * jt]);
        }
        cracks.push(pts);
      }
      // 描边分段：沿周长的切点
      const NS = 10, per = 2 * (w + h), cuts = [];
      for (let i = 0; i < NS; i++) cuts.push(((i + rng() * 0.8) / NS) * per);
      const segs = [];
      for (let i = 0; i < NS; i++) segs.push([rng(), rng(), rng()]);

      const f = flash(t);
      // 本体：闪白后迅速暗下
      const ba = 1 - outQuad(clamp01(t / 0.35));
      if (ba > 0) { ctx.fillStyle = rgba(c, ba, f * 0.7); ctx.fillRect(x, y, w, h); }

      // 裂纹：0~0.3 长出，0.45 后熄
      const grow = outCubic(clamp01(t / 0.3)), ca = 1 - outQuad(clamp01((t - 0.45) / 0.45));
      if (ca > 0) {
        ctx.lineCap = 'round'; ctx.lineJoin = 'round';
        ctx.beginPath();
        for (const pts of cracks) {
          const n = (pts.length - 1) * grow, full = Math.floor(n), fr = n - full;
          ctx.moveTo(pts[0][0], pts[0][1]);
          for (let k = 1; k <= full; k++) ctx.lineTo(pts[k][0], pts[k][1]);
          if (full < pts.length - 1) {
            const a = pts[full], b = pts[full + 1];
            ctx.lineTo(a[0] + (b[0] - a[0]) * fr, a[1] + (b[1] - a[1]) * fr);
          }
        }
        glowStroke(ctx, c, ca, (1.2 + f * 1.2) * s);
      }

      // 描边：0.3 前整圈，之后断成数截漂散熄灭
      const perim = d => { // 周长参数 → 点（顺时针，从左上角起）
        d = ((d % per) + per) % per;
        return d < w ? [x + d, y] : d < w + h ? [x + w, y + d - w]
             : d < 2 * w + h ? [x + w - (d - w - h), y + h] : [x, y + h - (d - 2 * w - h)];
      };
      const e = outQuad(clamp01((t - 0.3) / 0.7)), oa = 1 - e;
      if (oa <= 0) return;
      ctx.lineCap = 'butt'; ctx.lineJoin = 'miter';
      if (e === 0) {
        ctx.beginPath(); ctx.rect(x, y, w, h);
        glowStroke(ctx, c, 1, (1.5 + f * 1.5) * s);
        return;
      }
      const corners = [w, w + h, 2 * w + h];
      for (let i = 0; i < NS; i++) {
        const [dr, rt, sp] = segs[i];
        const d0 = cuts[i], d1 = i + 1 < NS ? cuts[i + 1] : cuts[0] + per;
        const pts = [perim(d0)];
        for (const cn of corners) { if (cn > d0 && cn < d1) pts.push(perim(cn)); if (cn + per > d0 && cn + per < d1) pts.push(perim(cn + per)); }
        pts.push(perim(d1));
        // 外法线（用中点相对中心的方向近似）+ 随机
        const m = perim((d0 + d1) / 2);
        let nx = m[0] - (x + w / 2), ny = m[1] - (y + h / 2);
        const nl = Math.hypot(nx, ny) || 1; nx /= nl; ny /= nl;
        const dist = e * (6 + sp * 16) * s;
        ctx.save();
        ctx.translate(m[0] + (nx + (dr - 0.5) * 0.6) * dist, m[1] + (ny + (dr - 0.5) * 0.6) * dist);
        ctx.rotate((rt - 0.5) * 0.35 * e);
        ctx.translate(-m[0], -m[1]);
        ctx.beginPath();
        ctx.moveTo(pts[0][0], pts[0][1]);
        for (let k = 1; k < pts.length; k++) ctx.lineTo(pts[k][0], pts[k][1]);
        glowStroke(ctx, c, oa * oa, 1.5 * s);
        ctx.restore();
      }
    }
  });

  // 5 ─────────────────────────────────────────────── 冲击光尘
  window.SHATTER.push({
    name: '冲击光尘',
    desc: '一圈冲击波从点击处荡开，波前扫过之处方块震成光尘四散',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h } = tile, c = hex(tile.color), s = Math.min(w, h) / 100;
      const cx = x + w * (0.35 + rng() * 0.3), cy = y + h * (0.35 + rng() * 0.3);
      const R = Math.hypot(Math.max(cx - x, x + w - cx), Math.max(cy - y, y + h - cy)); // 到最远角
      const N = 230, P = [];
      for (let i = 0; i < N; i++) P.push([rng(), rng(), rng(), rng(), rng()]);
      const F = tt => outQuad(clamp01(tt / 0.7)); // 波进度
      const f = flash(t), pr = F(t), r = pr * R;
      // 震：起手抖一下
      const sh = f * 2.2 * s;
      ctx.translate(Math.sin(t * 190) * sh, Math.cos(t * 140) * sh * 0.6);

      ctx.save();
      ctx.beginPath(); ctx.rect(x, y, w, h); ctx.clip();
      // 本体：波内已成尘，只剩波外
      if (pr < 1) {
        ctx.beginPath(); ctx.rect(x, y, w, h); ctx.arc(cx, cy, r, 0, TAU, true);
        ctx.fillStyle = rgba(c, 1, f * 0.7);
        ctx.fill('evenodd');
      }
      // 波前：内侧拖一圈渐隐余晖 + 亮边
      const ra = (1 - pr) ** 0.5;
      if (ra > 0.01 && r > 0.5) {
        const gw = Math.min(r, (10 + f * 6) * s);
        const g = ctx.createRadialGradient(cx, cy, r - gw, cx, cy, r);
        g.addColorStop(0, rgba(c, 0, 0.5));
        g.addColorStop(1, rgba(c, 0.55 * ra, 0.5));
        ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU);
        ctx.fillStyle = g; ctx.fill();
        glowStroke(ctx, c, ra, (2 + f * 2) * s);
      }
      ctx.restore();

      // 光尘：波扫到即离体，径向飞散
      ctx.globalCompositeOperation = 'lighter';
      for (const [u, v, sp, sz, wh] of P) {
        const px = x + u * w, py = y + v * h;
        const d = Math.hypot(px - cx, py - cy) || 1;
        const tb = 0.7 * (1 - Math.sqrt(1 - Math.min(d / R, 0.999))); // F(tb) = d/R
        if (t < tb) continue;
        const age = (t - tb) / (1 - tb), e = outCubic(age);
        const a = (1 - age) ** 1.5;
        if (a < 0.02) continue;
        const dist = e * (0.08 + sp * 0.2) * Math.min(w, h) * (0.5 + 0.5 * d / R);
        const qx = px + (px - cx) / d * dist, qy = py + (py - cy) / d * dist;
        const r2 = (0.45 + sz * sz * 1.1) * s * (1 - age * 0.5);
        ctx.fillStyle = rgba(c, a, wh > 0.7 ? 0.9 : 0.3);
        ctx.fillRect(qx - r2, qy - r2, r2 * 2, r2 * 2);
      }
    }
  });
})();
