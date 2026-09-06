// 钢琴块碎裂特效 · 物理 / 材质组（5 个）
// 契约：draw(ctx, tile, t, rng)，tile={x,y,w,h,color,seed}，t∈[0,1]（0.6s），rng 每帧从 seed 重来
// 不存任何状态：先用 rng 把所有碎块参数派生出来，再按 t 用解析式算位置（位置 = 初速×t + ½gt²）
(() => {
  window.SHATTER = window.SHATTER || [];

  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => v < a ? a : v > b ? b : v;
  const smooth = (a, b, x) => { x = clamp((x - a) / (b - a), 0, 1); return x * x * (3 - 2 * x); };
  // 主色深浅变体：k>0 向白靠、k<0 向黑靠
  const shade = (hex, k) => {
    const c = parseInt(hex.slice(1), 16), m = k < 0 ? 0 : 255, f = Math.abs(k);
    const ch = s => Math.round(((c >> s) & 255) * (1 - f) + m * f);
    return `rgb(${ch(16)},${ch(8)},${ch(0)})`;
  };

  // 上下圆角可分开的圆角矩形路径（不依赖 roundRect）
  const pill = (ctx, x, y, w, h, rt, rb) => {
    ctx.beginPath();
    ctx.moveTo(x + rt, y); ctx.arcTo(x + w, y, x + w, y + h, rt); ctx.arcTo(x + w, y + h, x, y + h, rb);
    ctx.arcTo(x, y + h, x, y, rb); ctx.arcTo(x, y, x + w, y, rt); ctx.closePath();
  };

  // 有地面的刚体运动（全解析）：抛物线 → 落地弹一次（弹性 e，水平速度打 6 折）→ 再落地就躺平、指数刹车
  // p = {x0,y0,vx,vy,spin,floor}（floor 是质心能到的最低 y）；返回 [x, y, 角度, 首次落地时刻, 落地速度]
  const rigid = (p, g, e, t) => {
    const t1 = (-p.vy + Math.sqrt(Math.max(0, p.vy * p.vy - 2 * g * (p.y0 - p.floor)))) / g;
    const vi = p.vy + g * t1;
    if (t < t1) return [p.x0 + p.vx * t, p.y0 + p.vy * t + 0.5 * g * t * t, p.spin * t, t1, vi];
    const vb = -e * vi, u = t - t1, u2 = -2 * vb / g;
    if (u < u2) return [p.x0 + p.vx * (t1 + 0.6 * u), p.floor + vb * u + 0.5 * g * u * u, p.spin * (t1 + 0.5 * u), t1, vi];
    const s = t1 + 0.6 * u2 + 0.6 * (1 - Math.exp(-10 * (u - u2))) / 10;   // 第二次落地后滑行距离（等效时间）
    return [p.x0 + p.vx * s, p.floor, p.spin * (s - 0.1 * u2), t1, vi];
  };

  // 1 砖块崩塌 ————————————————————————————————————————————————
  window.SHATTER.push({
    name: '砖块崩塌',
    desc: '错缝砖墙从点击处松动塌下来，砖块带重力落地弹一下、扬起一点灰',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h, color } = tile;
      const rows = clamp(Math.round(h / 26), 2, 8), cols = clamp(Math.round(w / 34), 2, 5);
      const bh = h / rows, bw = w / cols, g = 16 * h;
      const ix = x + w / 2, iy = y + h / 2;                    // 冲击点
      const light = shade(color, 0.3), dark = shade(color, -0.4);
      const fade = 1 - smooth(0.4, 1, t), M = ctx.getTransform();
      for (let r = 0; r < rows; r++) {
        const y0 = y + (r + 0.5) * bh;
        for (let l = x - (r % 2) * bw / 2; l < x + w - 0.5; l += bw) {   // 奇数行错半块
          const a = Math.max(l, x), b = Math.min(l + bw, x + w), pw = b - a, x0 = (a + b) / 2;
          const dx = x0 - ix, dy = y0 - iy, d = Math.hypot(dx, dy) || 1;
          const sp = (0.35 + 0.45 * rng()) * w * Math.max(0.3, 1 - d / w);   // 离冲击点越近推得越猛
          const p = { x0, y0, floor: y + h - bh / 2 - rng() * bh * 0.7,   // 落点高低不一，堆成一堆而不是一排
            vx: dx / d * sp + (rng() - 0.5) * 0.3 * w,
            vy: -(0.2 + 0.9 * rng()) * h, spin: (rng() - 0.5) * 6 };
          p.spin += p.vx / w * 3;
          const tau = Math.max(0, t - d / w * 0.2);                  // 裂缝从冲击点往外传，远处的砖晚一点才松
          const [px, py, ang, t1, vi] = rigid(p, g, 0.35, tau);
          ctx.setTransform(M);
          // 落地扬灰：一小团淡色圆，随时间扩散消散
          const u = tau - t1;
          if (u > 0 && u < 0.25) {
            ctx.globalAlpha = fade * 0.16 * (1 - u / 0.25) * clamp(vi / (2 * h), 0.3, 1);
            ctx.fillStyle = light;
            ctx.beginPath(); ctx.arc(px, y + h - 1, bw * 0.2 + u * w * 0.7, 0, TAU); ctx.fill();
          }
          ctx.translate(px, py); ctx.rotate(ang);
          ctx.globalAlpha = fade;
          ctx.fillStyle = color; ctx.fillRect(-pw / 2 + 0.6, -bh / 2 + 0.6, pw - 1.2, bh - 1.2);
          ctx.fillStyle = light; ctx.fillRect(-pw / 2 + 0.6, -bh / 2 + 0.6, pw - 1.2, 1.2);   // 上沿高光
          ctx.fillStyle = dark; ctx.fillRect(-pw / 2 + 0.6, bh / 2 - 2.4, pw - 1.2, 1.8);     // 下沿阴影
        }
      }
      ctx.setTransform(M);
    }
  });

  // 2 纸片撕裂 ————————————————————————————————————————————————
  window.SHATTER.push({
    name: '纸片撕裂',
    desc: '撕成毛边纸片，轻飘飘地摇着翻面落下，翻过来是浅色纸背',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h, color } = tile;
      const n = clamp(Math.round(w / 20), 3, 7), m = clamp(Math.round(h / 10), 4, 20);
      const q = clamp(Math.round(h / 45), 1, 4);                       // 每条纸条横着撕成 q 段
      const sw = w / n, sh = h / m;
      // n+1 条上下贯穿的毛边撕口（首尾两条是方块直边）
      const edges = [];
      for (let j = 0; j <= n; j++) {
        const e = [];
        for (let k = 0; k <= m; k++)
          e.push([x + j * sw + (j > 0 && j < n ? (rng() - 0.5) * sw * 0.4 : 0), y + k * sh]);
        edges.push(e);
      }
      const back = shade(color, 0.6), fiber = 'rgba(255,255,255,0.35)';
      const fade = 1 - smooth(0.45, 1, t), M = ctx.getTransform();
      ctx.lineWidth = 0.8; ctx.lineJoin = 'round';
      for (let j = 0; j < n; j++) {
        const L = edges[j], R = edges[j + 1];
        // 横撕口所在的行号（含首尾），每道口子两个毛边点
        const cuts = [0], tears = [null];
        for (let i = 1; i < q; i++) {
          const kc = clamp(Math.round(m * (i / q + (rng() - 0.5) * 0.2)), 1, m - 1);
          cuts.push(kc);
          tears.push([1, 2].map(u => [L[kc][0] + (R[kc][0] - L[kc][0]) * u / 3, y + kc * sh + (rng() - 0.5) * sh]));
        }
        cuts.push(m); tears.push(null);
        for (let seg = 0; seg < q; seg++) {
          const a = cuts[seg], b = cuts[seg + 1];
          const poly = [...L.slice(a, b + 1), ...(tears[seg + 1] || []), ...R.slice(a, b + 1).reverse(), ...(tears[seg] ? tears[seg].slice().reverse() : [])];
          let cx = 0, cy = 0;
          for (const v of poly) { cx += v[0]; cy += v[1]; }
          cx /= poly.length; cy /= poly.length;
          // 运动参数：上面的先走；向两侧散、左右摇、来回扭、三成的纸片会翻面
          const d = seg * 0.08 + rng() * 0.08;
          const vx = ((j + 0.5) / n - 0.5) * w * 1.5 * (0.5 + rng()), vy = (0.05 + 0.15 * rng()) * h, gp = (0.4 + 0.6 * rng()) * h;   // 每片下落快慢不一，免得堆成一坨
          const A = 0.1 * w, om = 8 + 5 * rng(), ph = rng() * TAU;
          const R0 = 0.3 + 0.2 * rng(), om2 = 6 + 4 * rng(), ph2 = rng() * TAU, drift = (rng() - 0.5) * 1.5;
          const omf = rng() < 0.7 ? 0 : 4 + 3 * rng();
          const tau = Math.max(0, t - d);
          const px = cx + vx * tau + A * (Math.sin(om * tau + ph) - Math.sin(ph));
          const py = cy + vy * tau + gp * tau * tau;
          const ang = R0 * (Math.sin(om2 * tau + ph2) - Math.sin(ph2)) + drift * tau;
          const flip = Math.cos(omf * tau);
          ctx.setTransform(M); ctx.translate(px, py); ctx.rotate(ang); ctx.scale(Math.max(0.3, Math.abs(flip)), 1);
          ctx.globalAlpha = fade;
          ctx.beginPath();
          for (const v of poly) ctx.lineTo(v[0] - cx, v[1] - cy);
          ctx.closePath();
          ctx.fillStyle = flip < 0 ? back : color; ctx.fill();
          if (Math.abs(flip) > 0.35) { ctx.strokeStyle = fiber; ctx.stroke(); }   // 侧过来时不描边，免得成一根棍
        }
      }
      ctx.setTransform(M);
    }
  });

  // 3 压扁弹回 ————————————————————————————————————————————————
  window.SHATTER.push({
    name: '压扁弹回',
    desc: '先被压扁、再弹起拉长，绷不住散成一把细碎屑落地',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h, color } = tile;
      const cx = x + w / 2, floor = y + h, tb = 0.28;             // tb：崩散时刻
      const light = shade(color, 0.4), M = ctx.getTransform();
      // 碎屑（先画，主体盖在上面）：24 块小渣 + 140 粒细屑，从崩散瞬间的主体里飞出，向两侧撑开 + 向上抛，带重力落地弹一下
      const g = 12 * h, cap = 1 - smooth(0.5, 1, t), unit = w / 120;
      for (let i = 0; i < 164; i++) {
        const lx = (rng() - 0.5) * w * 0.8, ly = -rng() * h * 1.25;
        const size = (i < 24 ? 4 + 5 * rng() : 1.5 + 2.5 * rng()) * unit;
        const p = { x0: cx + lx, y0: floor + ly, floor: floor - size / 2,
          vx: lx / w * 1.6 * w * (0.6 + 0.8 * rng()), vy: -(0.4 + 1.4 * rng()) * h, spin: (rng() - 0.5) * 12 };
        const life = 0.35 + 0.35 * rng(), pale = rng() < 0.3;
        const tau = t - tb;
        if (tau <= 0) continue;
        const a = Math.min(cap, 1 - tau / life);
        if (a <= 0) continue;
        const [px, py, ang] = rigid(p, g, 0.3, tau);
        ctx.setTransform(M); ctx.translate(px, py); ctx.rotate(ang);
        ctx.globalAlpha = a; ctx.fillStyle = pale ? light : color;
        ctx.fillRect(-size / 2, -size / 2, size, size);
      }
      ctx.setTransform(M);
      // 主体（画在碎屑上面，散掉前盖住碎屑）：底边固定，竖向 1 → 0.55（压扁）→ 1.3（弹起拉长）→ 崩散后继续胀大并消失
      const sy = t < 0.14 ? 1 - 0.45 * smooth(0, 0.14, t) : t < tb ? 0.55 + 0.75 * smooth(0.14, tb, t) : 1.3 + (t - tb) * 1.5;
      const sx = 1 + (1 - sy) * 0.7;
      const bodyA = 1 - smooth(tb + 0.03, tb + 0.11, t);
      if (bodyA > 0) {
        ctx.globalAlpha = bodyA; ctx.fillStyle = color;
        ctx.fillRect(cx - w * sx / 2, floor - h * sy, w * sx, h * sy);
      }
      // 触地那一下：底边一道白光，压得越狠越宽
      const ga = 0.5 * smooth(0, 0.04, t) * (1 - smooth(0.06, 0.3, t));
      if (ga > 0) {
        ctx.globalAlpha = ga; ctx.fillStyle = '#fff';
        ctx.fillRect(cx - w * sx * 0.6, floor - 0.75, w * sx * 1.2, 1.5);
      }
      ctx.setTransform(M);
    }
  });

  // 4 墨滴溅开 ————————————————————————————————————————————————
  window.SHATTER.push({
    name: '墨滴溅开',
    desc: '方块化成一滩墨塌下去摊开，墨点甩出去拉成水滴、落回地面',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h, color } = tile;
      const cx = x + w / 2, floor = y + h, g = 9 * h;
      const dark = shade(color, -0.3), M = ctx.getTransform();
      // 主体：从整块塌成一滩——顶边往地面沉、宽度摊开、角变圆；边缘鼓出几个圆包成溅开的形状
      const k = smooth(0, 0.55, t);
      const ph = h * (1 - 0.92 * k), pw = w * (1 + 0.55 * smooth(0.05, 0.7, t));
      const rad = Math.min(pw, ph) / 2 * clamp(k * 1.4, 0, 1);
      ctx.globalAlpha = 1 - smooth(0.55, 1, t);
      ctx.fillStyle = color;
      pill(ctx, cx - pw / 2, floor - ph, pw, ph, rad, rad); ctx.fill();
      const bump = smooth(0.15, 0.6, t);
      for (let i = 0; i < 8; i++) {
        const side = i < 4 ? -1 : 1;
        const bx = cx + side * pw / 2 * (0.7 + 0.35 * rng()), by = floor - ph * (0.2 + 0.7 * rng());
        const br = ph * (0.3 + 0.4 * rng()) * bump;
        ctx.beginPath(); ctx.arc(bx, by, br, 0, TAU); ctx.fill();
      }
      // 底部沉一层更深的墨
      ctx.fillStyle = dark;
      pill(ctx, cx - pw / 2, floor - ph * 0.3 * k, pw, ph * 0.3 * k, 0, Math.min(rad, ph * 0.3 * k)); ctx.fill();
      // 墨滴：28 主滴 + 40 小星点，从方块上半部向上扇形甩出，沿速度方向拉长
      const ox = cx, oy = y + h * 0.35, unit = Math.min(w, h) / 120;
      const dropA = 1 - smooth(0.4, 1, t);
      for (let i = 0; i < 68; i++) {
        const small = i >= 28;
        const th = (rng() - 0.5) * 2.6, s = 0.5 + 1.4 * rng();
        const vx = Math.sin(th) * s * w * 0.9, vy = -Math.cos(th) * s * h * 1.1 - 0.2 * h;
        const r = (small ? 0.6 + 0.7 * rng() : 1.2 + 2.2 * rng()) * unit;
        const deep = rng() < 0.3;
        const t1 = (-vy + Math.sqrt(vy * vy + 2 * g * (floor - oy))) / g;   // 落回地面时刻
        ctx.fillStyle = deep ? dark : color;
        if (t < t1) {
          const vyt = vy + g * t, sp = Math.hypot(vx, vyt), el = clamp(1 + sp / (3 * h), 1, 2.2);
          ctx.setTransform(M); ctx.translate(ox + vx * t, oy + vy * t + 0.5 * g * t * t); ctx.rotate(Math.atan2(vyt, vx));
          ctx.globalAlpha = dropA;
          ctx.beginPath(); ctx.ellipse(0, 0, r * el, r / Math.sqrt(el), 0, 0, TAU); ctx.fill();
        } else if (t - t1 < 0.2 && !small) {
          // 落地摊成一小片，很快没入地面
          ctx.setTransform(M); ctx.globalAlpha = dropA * (1 - (t - t1) / 0.2);
          ctx.beginPath(); ctx.ellipse(ox + vx * t1, floor - r * 0.3, r * 1.8, r * 0.45, 0, 0, TAU); ctx.fill();
        }
      }
      ctx.setTransform(M);
    }
  });

  // 5 积木塌落 ————————————————————————————————————————————————
  window.SHATTER.push({
    name: '积木塌落',
    desc: '一格格小方块从底层开始失去支撑，塌下来滚开、在地上滑一段停住',
    draw(ctx, tile, t, rng) {
      const { x, y, w, h, color } = tile;
      const s = clamp(Math.min(w, h) / 4, 10, 26);
      const cols = clamp(Math.round(w / s), 2, 8), rows = clamp(Math.round(h / s), 2, 12);
      const bw = w / cols, bh = h / rows, g = 18 * h;
      const light = shade(color, 0.35), dark = shade(color, -0.35);
      const fade = 1 - smooth(0.45, 1, t), M = ctx.getTransform();
      ctx.globalAlpha = fade;
      for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
        const x0 = x + (c + 0.5) * bw, y0 = y + (r + 0.5) * bh;
        const side = (c + 0.5) / cols - 0.5;                            // -0.5..0.5，左负右正
        const bottom = r === rows - 1;
        const d = (rows - 1 - r) * 0.18 / rows + rng() * 0.06;          // 底层先塌、逐层往上
        const p = { x0, y0, floor: y + h - bh / 2 - (bottom ? 0 : rng() * bh * 0.8),   // 上层的落在别的块上，堆起来
          vx: (bottom ? Math.sign(side || 1) * (0.5 + 0.7 * rng()) : side * (1.2 + 1.5 * rng())) * w,
          vy: -(bottom ? 0.4 + 0.8 * rng() : 0.1 + 0.4 * rng()) * h, spin: 0 };
        p.spin = p.vx / w * 4 + (rng() - 0.5) * 3;
        const tau = t - d;
        const [px, py, ang] = tau > 0 ? rigid(p, g, 0.25, tau) : [x0, y0, 0];
        ctx.setTransform(M); ctx.translate(px, py); ctx.rotate(ang);
        ctx.fillStyle = color; ctx.fillRect(-bw / 2 + 0.5, -bh / 2 + 0.5, bw - 1, bh - 1);
        ctx.fillStyle = light; ctx.fillRect(-bw / 2 + 0.5, -bh / 2 + 0.5, bw - 1, bh * 0.2);               // 顶面受光
        ctx.fillStyle = dark; ctx.fillRect(-bw / 2 + 0.5, bh / 2 - 0.5 - bh * 0.14, bw - 1, bh * 0.14);   // 底面背光
      }
      ctx.setTransform(M);
    }
  });
})();
