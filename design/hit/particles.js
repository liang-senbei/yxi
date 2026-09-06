// 点击特效 · 粒子 / 光尘 · 6 个
// 契约：draw(ctx, hit, t, rng)。原点已平移到命中点，x 沿判定线、y 向上为负；hit={u,color,judge,noteH}；
// t∈[0,1]（宿主 0.5 秒播完）；rng 每帧按同 seed 重开 —— 粒子参数每帧从 rng 重新派生，位置全是解析式，不存状态。
// 注意：每颗粒子的 rng() 必须先取完再 continue，否则后面粒子的序列会错位、逐帧抖。
(() => {
  const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  // k>0 向白混，k<0 向黑混
  const rgba = (c, a, k = 0) => {
    const f = k > 0 ? v => v + (255 - v) * k : v => v * (1 + k);
    return `rgba(${f(c[0]) | 0},${f(c[1]) | 0},${f(c[2]) | 0},${a < 0 ? 0 : a > 1 ? 1 : a})`;
  };
  const TAU = Math.PI * 2;
  const min1 = v => v < 1 ? v : 1;
  // 阻力式爆出 0→1：τ 越小前段越陡（τ=0.07 时 t=0.1 已到 76%，0.25 到 97%）
  const burst = (t, tau = 0.07) => 1 - Math.exp(-t / tau);
  // 寿命 L 内淡出 (1-t/L)^p，过了寿命返回 0
  const fade = (t, L = 1, p = 1.6) => { const k = 1 - t / L; return k <= 0 ? 0 : k ** p; };
  // 初生白热，T 内回本色
  const heat = (t, T = 0.25) => 0.9 * (1 - min1(t / T));
  // good 比 perfect 少几颗、小一圈
  const cnt = (hit, n) => hit.judge === 'perfect' ? n : Math.round(n * 0.7);
  const sc = hit => hit.judge === 'perfect' ? 1 : 0.85;

  window.HITFX = window.HITFX || [];

  // 1 ─────────────────────────────────────────────── 方粒
  window.HITFX.push({
    name: '方粒',
    desc: '十来颗小方块弹出，减速漂散、微微翻转后淡出',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 14);
      // 中心一点白热，0.1 内缩没
      if (t < 0.1) {
        const k = t / 0.1;
        ctx.fillStyle = rgba(c, 0.9 * (1 - k), 0.9);
        ctx.beginPath(); ctx.arc(0, 0, u * 0.4 * (1 - 0.7 * k), 0, TAU); ctx.fill();
      }
      const e = burst(t);
      for (let i = 0; i < N; i++) {
        const ang = (i / N) * TAU + (rng() - 0.5) * 0.6;   // 均匀撒一圈再抖，不扎堆
        const R = u * (0.9 + rng() * 1.4) * s;              // 减速后停在 0.9u~2.3u，远近错开别排成圈
        const sz = u * (0.15 + rng() * 0.17);
        const spin = (rng() - 0.5) * 2.5;
        const L = 0.7 + rng() * 0.3;
        const a = fade(t, L);
        if (a <= 0) continue;
        const cx = Math.cos(ang), sy = Math.sin(ang);
        // 阻力减速 + 一点残余外漂 + 轻微下坠
        const x = cx * (R * e + u * 0.25 * t);
        const y = sy * (R * e + u * 0.25 * t) + u * 0.5 * t * t;
        const g = sz * (1 + 0.3 * e);                        // 边飞边略变大
        ctx.save();
        ctx.translate(x, y); ctx.rotate(spin * e);
        ctx.fillStyle = rgba(c, a, heat(t));
        ctx.fillRect(-g / 2, -g / 2, g, g);
        ctx.restore();
      }
    }
  });

  // 2 ─────────────────────────────────────────────── 火花
  window.HITFX.push({
    name: '火花',
    desc: '火星往上喷成扇面，被重力拽回，拖着短尾落下',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 24);
      // 落点一小片横向白热，很快熄
      if (t < 0.1) {
        const k = t / 0.1;
        ctx.fillStyle = rgba(c, 0.95 * (1 - k), 0.95);
        ctx.beginPath(); ctx.ellipse(0, 0, u * 0.7 * (1 - 0.5 * k), u * 0.18, 0, 0, TAU); ctx.fill();
      }
      ctx.lineCap = 'round';
      const G = u * 5.5;                                     // 重力
      for (let i = 0; i < N; i++) {
        const ang = -Math.PI / 2 + (rng() - 0.5) * 1.5;      // 朝上 ±43° 的扇面
        const v = u * (1.8 + rng() * 1.2) * s;
        const w = u * (0.06 + rng() * 0.07);
        const L = 0.55 + rng() * 0.35;                       // 最晚 0.9 灭，落到线下一点点就熄，别一路掉出画
        const a = fade(t, L, 1.2);
        if (a <= 0) continue;
        const cx = Math.cos(ang), sy = Math.sin(ang);
        const at = tt => { const e = burst(tt, 0.09); return [cx * v * e, sy * v * e + G * tt * tt]; };
        const [x, y] = at(t), [x0, y0] = at(t > 0.045 ? t - 0.045 : 0);   // 尾巴 = 4.5% 时长前的位置
        ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x, y);
        ctx.strokeStyle = rgba(c, a * 0.25); ctx.lineWidth = w * 3; ctx.stroke();   // 软晕
        ctx.strokeStyle = rgba(c, a, heat(t, 0.35)); ctx.lineWidth = w * (1.2 - 0.6 * t); ctx.stroke();
      }
    }
  });

  // 3 ─────────────────────────────────────────────── 螺旋尘
  window.HITFX.push({
    name: '螺旋尘',
    desc: '光尘顺着两条旋臂转着散开，转速渐停、边散边冷却',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 80);
      const e = burst(t, 0.08);
      for (let i = 0; i < N; i++) {
        const q = rng();                                       // 在臂上的位置 0(根)..1(梢)
        const R = u * (0.4 + 2.1 * q) * s * (0.85 + rng() * 0.3);
        const ang0 = (i & 1) * Math.PI + q * 1.8 + (rng() - 0.5) * 0.7;   // 两臂对开，越外越扭
        const r = u * (0.025 + rng() * rng() * 0.09);                      // 多数很细，偶有一两颗大的
        const L = 0.55 + rng() * 0.45;
        const a = fade(t, L, 1.4);
        if (a <= 0) continue;
        const ang = ang0 + 1.3 * e;                            // 整体再旋 ~75°，跟着减速停
        const rr = R * e + u * 0.2 * t;
        const x = Math.cos(ang) * rr, y = Math.sin(ang) * rr;
        ctx.fillStyle = rgba(c, a * 0.18, 0.1);                // 软晕
        ctx.beginPath(); ctx.arc(x, y, r * 2 + u * 0.03, 0, TAU); ctx.fill();
        ctx.fillStyle = rgba(c, a, 0.35 + 0.6 * (1 - min1(t / 0.3)));   // 亮芯，由白热回本色
        ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.fill();
      }
    }
  });

  // 4 ─────────────────────────────────────────────── 聚爆
  window.HITFX.push({
    name: '聚爆',
    desc: '一圈光点先向中心收拢，攒住一瞬再炸开，越飞越慢',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 18);
      const T0 = 0.08;                                         // 收拢用时
      const tt = (t - T0) / (1 - T0);                          // 炸开后的进度
      ctx.lineCap = 'round';
      for (let i = 0; i < N; i++) {
        const ang = (i / N) * TAU + (rng() - 0.5) * 0.3;
        const R0 = u * (0.9 + rng() * 0.5);                    // 起手环
        const R1 = u * (1.8 + rng() * 0.7) * s;                // 炸开落点
        const r = u * (0.07 + rng() * 0.07);
        const L = 0.7 + rng() * 0.3;
        const cx = Math.cos(ang), sy = Math.sin(ang);
        if (t < T0) {
          const k = t / T0, rr = u * 0.2 + (R0 - u * 0.2) * (1 - k * k);   // ease-in 收拢到 0.2u
          ctx.fillStyle = rgba(c, 0.5 + 0.5 * k, 0.3 + 0.6 * k);
          ctx.beginPath(); ctx.arc(cx * rr, sy * rr, r, 0, TAU); ctx.fill();
          continue;
        }
        const a = fade(tt, L, 1.5);
        if (a <= 0) continue;
        const at = k => u * 0.2 + (R1 - u * 0.2) * burst(k, 0.06) + u * 0.2 * k;
        const rr = at(tt), r0 = at(tt > 0.03 ? tt - 0.03 : 0);   // 刚炸开拖一小截尾，减速后收成点
        ctx.strokeStyle = rgba(c, a, heat(tt, 0.2));
        ctx.lineWidth = r * 2;
        ctx.beginPath(); ctx.moveTo(cx * r0, sy * r0); ctx.lineTo(cx * rr, sy * rr); ctx.stroke();
      }
      // 攒到中心那一瞬的闪光：T0 峰值，之后 0.1 内胀开熄掉
      if (t > T0 * 0.5 && t < T0 + 0.1) {
        const k = t < T0 ? (t - T0 * 0.5) / (T0 * 0.5) : 1 - (t - T0) / 0.1;
        ctx.fillStyle = rgba(c, 0.95 * k, 0.9);
        ctx.beginPath(); ctx.arc(0, 0, u * 0.35 * (t < T0 ? k : 1 + (1 - k) * 0.8), 0, TAU); ctx.fill();
      }
    }
  });

  // 5 ─────────────────────────────────────────────── 光丝
  window.HITFX.push({
    name: '光丝',
    desc: '十几根细长光丝射出去，到头略停，再从尖端缩回熄灭',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 16);
      // 中心锚点，0.5 内淡掉
      if (t < 0.5) {
        ctx.fillStyle = rgba(c, 0.9 * (1 - t / 0.5), 0.9);
        ctx.beginPath(); ctx.arc(0, 0, u * 0.2 * (1 - 0.5 * t), 0, TAU); ctx.fill();
      }
      for (let i = 0; i < N; i++) {
        const ang = (i / N) * TAU + (rng() - 0.5) * 0.35;
        const R = u * (1.5 + rng() * 1.2) * s;
        const d = rng() * 0.06;                                // 各根略有先后
        const w = u * (0.05 + rng() * 0.06);                   // 根部宽
        const tt = t - d;
        if (tt <= 0) continue;
        // 尖端：0.15 内射到头 → 停到 0.3 → 0.85 缩回中心；根部离中心一点点，跟着一起缩
        const out = burst(tt, 0.045);
        const back = tt < 0.3 ? 0 : min1((tt - 0.3) / 0.55) ** 1.7;
        const tip = R * out * (1 - back), root = u * 0.04 + u * 0.14 * out * (1 - back);
        if (tip - root < u * 0.05) continue;
        const a = (1 - back) * fade(tt, 1, 0.8);
        const cx = Math.cos(ang), sy = Math.sin(ang), px = -sy, py = cx;
        // 根宽尖细的三角：先铺一层淡的宽晕，再铺亮芯
        for (const [ww, aa, k] of [[w * 3, a * 0.22, 0.2], [w, a, 0.5 + 0.5 * heat(tt, 0.2)]]) {
          ctx.fillStyle = rgba(c, aa, k);
          ctx.beginPath();
          ctx.moveTo(cx * root + px * ww / 2, sy * root + py * ww / 2);
          ctx.lineTo(cx * root - px * ww / 2, sy * root - py * ww / 2);
          ctx.lineTo(cx * tip, sy * tip);
          ctx.closePath(); ctx.fill();
        }
      }
    }
  });

  // 6 ─────────────────────────────────────────────── 横溅
  window.HITFX.push({
    name: '横溅',
    desc: '沿判定线向两侧横着溅开，贴着线弹跳、越滑越慢',
    draw(ctx, hit, t, rng) {
      const { u } = hit, c = hex(hit.color), s = sc(hit), N = cnt(hit, 18);
      // 沿线一道亮痕：从中心向两侧扫出，两端渐隐，0.3 内熄
      if (t < 0.3) {
        const k = burst(t, 0.05), a = 0.8 * (1 - t / 0.3);
        const half = u * 2.4 * s * k, h = Math.max(1, hit.noteH * 0.15);
        const g = ctx.createLinearGradient(-half, 0, half, 0);
        g.addColorStop(0, rgba(c, 0, 0.5)); g.addColorStop(0.5, rgba(c, a, 0.5)); g.addColorStop(1, rgba(c, 0, 0.5));
        ctx.fillStyle = g; ctx.fillRect(-half, -h / 2, half * 2, h);
      }
      for (let i = 0; i < N; i++) {
        const dir = i & 1 ? 1 : -1;                            // 左右各半
        const v = u * (0.6 + rng() * 1.9) * s;
        const h = u * (0.25 + rng() * 0.9);                    // 弹起高度
        const P = 0.22 + rng() * 0.3;                          // 弹跳周期
        const sz = u * (0.06 + rng() * 0.07);
        const L = 0.6 + rng() * 0.4;
        const d = rng() * 0.05;                                // 前后脚出发，起手不糊成一坨
        const tt = t - d;
        if (tt <= 0) continue;
        const a = fade(tt, L, 1.3);
        if (a <= 0) continue;
        const e = burst(tt, 0.06);
        const x = dir * (v * e + u * 0.3 * tt);
        const y = -h * Math.abs(Math.sin(Math.PI * tt / P)) * (1 - tt) ** 1.5;   // 越弹越矮，贴着线
        const len = sz * (1 + 2 * (1 - e));                    // 快时拉长，慢了收成点
        ctx.fillStyle = rgba(c, a, heat(tt, 0.15));
        ctx.beginPath(); ctx.ellipse(x, y, len, sz * 0.7, 0, 0, TAU); ctx.fill();
      }
    }
  });
})();
