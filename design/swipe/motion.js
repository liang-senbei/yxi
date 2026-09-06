// swipe 方向标记 · 运动 / 流动类 5 款。
// 契约：draw(ctx, note, t, rng) 画完整音符；note = {x, y, w, h, color, dir, lineY, laneX, laneW}，
// t 是 0..1 循环相位，rng 每帧同 seed 重开。静止帧靠形状分方向，动画只是加分。
(() => {
  'use strict';
  const A = (hex, a) => { const n = parseInt(hex.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; };
  const W = a => `rgba(255,255,255,${a})`;
  const rr = (ctx, x, y, w, h, r) => {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath(); ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  };
  const fill = (ctx, style) => { ctx.fillStyle = style; ctx.fill(); };
  const dot = (ctx, x, y, r, style) => { ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); fill(ctx, style); };
  const grad = (ctx, x0, x1, stops) => { const g = ctx.createLinearGradient(x0, 0, x1, 0); for (const [o, c] of stops) g.addColorStop(o, c); return g; };
  // 五款都只画「朝右」的：坐标系摆成原点在音符中心、+x = 滑动方向
  const face = (ctx, n) => { ctx.translate(n.x, n.y); ctx.scale(n.dir < 0 ? -1 : 1, 1); };

  window.SWIPE = window.SWIPE || [];

  // 1. 流光 —— 彗星：尾尖头圆，白芯往头越来越亮，一道脉冲沿芯朝方向跑
  window.SWIPE.push({
    name: '流光',
    desc: '彗星形薄片：尾端收尖、头端圆亮，一道白光脉冲沿芯朝滑动方向跑',
    draw(ctx, n, t) {
      face(ctx, n);
      const { w, h, color: c } = n, L = -w / 2, R = w / 2;
      const comet = (L, R, hh) => {
        const k = R - L;
        ctx.beginPath(); ctx.moveTo(L, 0);
        ctx.quadraticCurveTo(L + k * .12, -hh, L + k * .42, -hh); ctx.lineTo(R - hh, -hh);
        ctx.arc(R - hh, 0, hh, -Math.PI / 2, Math.PI / 2);
        ctx.lineTo(L + k * .42, hh); ctx.quadraticCurveTo(L + k * .12, hh, L, 0); ctx.closePath();
      };
      comet(L - 2, R + 2, h / 2 + 2); fill(ctx, A(c, .22));
      comet(L, R, h / 2); fill(ctx, grad(ctx, L, R, [[0, A(c, .4)], [.55, c], [1, c]]));
      comet(L + w * .2, R - h * .3, h * .22); fill(ctx, grad(ctx, L + w * .2, R, [[0, W(0)], [1, W(.95)]]));
      const p = L + w * .25 + t * w * .7, k = Math.sin(Math.PI * t);      // 脉冲：起止渐隐
      rr(ctx, p - h * .8, -h * .2, h * 1.6, h * .4, h * .2); fill(ctx, W(.85 * k));
      dot(ctx, R - h * .5, 0, h * .28, '#fff');
    },
  });

  // 2. 跑马 —— 子弹头薄片，芯里一排小楔子 ▸ 亮点从尾到头一段段跑过去
  window.SWIPE.push({
    name: '跑马',
    desc: '子弹头薄片，芯槽里一排小楔子 ▸，亮点从尾到头一段段跑过去',
    draw(ctx, n, t) {
      face(ctx, n);
      const { w, h, color: c } = n, L = -w / 2, R = w / 2, hh = h / 2;
      const bullet = (L, R, hh, tip) => {
        ctx.beginPath(); ctx.arc(L + hh, 0, hh, Math.PI / 2, Math.PI * 1.5);
        ctx.lineTo(R - tip, -hh); ctx.lineTo(R, 0); ctx.lineTo(R - tip, hh); ctx.closePath();
      };
      bullet(L - 2, R + 2, hh + 2, h * 1.1); fill(ctx, A(c, .22));
      bullet(L, R, hh, h * .9); fill(ctx, c);
      const x0 = L + h * .9, x1 = R - h * 1.8;
      rr(ctx, x0 - h * .45, -h * .22, x1 - x0 + h * .9, h * .44, h * .22); fill(ctx, 'rgba(0,0,0,.28)');   // 芯槽
      const N = Math.max(4, Math.round((x1 - x0) / (h * .85))), uc = t * 1.6 - .3;   // 亮点中心：从尾外进来、从头外出去，循环处不跳
      for (let i = 0; i < N; i++) {
        const x = x0 + (x1 - x0) * i / (N - 1), lit = Math.max(0, 1 - Math.abs(i / (N - 1) - uc) / .3);
        ctx.beginPath(); ctx.moveTo(x - h * .25, -h * .28); ctx.lineTo(x + h * .3, 0); ctx.lineTo(x - h * .25, h * .28); ctx.closePath();
        fill(ctx, W(.45 + .55 * lit));
      }
      ctx.beginPath(); ctx.moveTo(R - h * 1.25, -hh * .8); ctx.lineTo(R - h * .08, 0); ctx.lineTo(R - h * 1.25, hh * .8); ctx.closePath();   // 白尖：远看就靠它
      fill(ctx, '#fff');
    },
  });

  // 3. 风偏 —— 整片斜体倒向滑动方向，尾后拖三道风线，本体一下一下被往那边推
  window.SWIPE.push({
    name: '风偏',
    desc: '薄片斜体倒向滑动方向，尾后拖三道风线；本体一下一下被往那边推',
    draw(ctx, n, t, rng) {
      face(ctx, n);
      const { w, h, color: c } = n, L = -w / 2, hh = h / 2;
      const s = t < .25 ? Math.sin(t / .25 * Math.PI / 2) : Math.cos((t - .25) / .75 * Math.PI / 2);   // 快推慢回
      ctx.translate(h * .35 * s, 0);
      ctx.lineWidth = 1.2; ctx.lineCap = 'round';
      for (let i = -1; i <= 1; i++) {
        const len = w * (i ? .3 : .45) * (.8 + .2 * Math.sin(2 * Math.PI * (t + rng())));
        const x1 = L - h * .45 - (i ? h * .45 : 0), y = i * h * .36;
        ctx.strokeStyle = grad(ctx, x1 - len, x1, [[0, A(c, 0)], [1, A(c, .9)]]);
        ctx.beginPath(); ctx.moveTo(x1 - len, y); ctx.lineTo(x1, y); ctx.stroke();
      }
      ctx.transform(1, 0, -.7, 1, 0, 0);    // 斜体：上沿往方向偏（圆角要小，圆头斜了看不出来）
      rr(ctx, L - 2, -hh - 2, w + 4, h + 4, h * .3); fill(ctx, A(c, .22));
      rr(ctx, L, -hh, w, h, h * .2); fill(ctx, c);
      rr(ctx, L + h * .5, -h * .22, w - h, h * .44, h * .1); fill(ctx, W(.85));
    },
  });

  // 4. 散粒 —— 头端收成喷口，朝滑动方向不断喷出小光粒，扇面越远越散越淡
  window.SWIPE.push({
    name: '散粒',
    desc: '头端收成喷口，朝滑动方向不断喷出小光粒，扇面越远越散越淡',
    draw(ctx, n, t, rng) {
      face(ctx, n);
      const { w, h, color: c } = n, L = -w / 2, R = w / 2, hh = h / 2;
      const nozzle = (L, R, hh, m) => {
        ctx.beginPath(); ctx.arc(L + hh, 0, hh, Math.PI / 2, Math.PI * 1.5);
        ctx.lineTo(R - h * 1.3, -hh); ctx.lineTo(R, -m); ctx.lineTo(R, m); ctx.lineTo(R - h * 1.3, hh); ctx.closePath();
      };
      nozzle(L - 2, R + 2, hh + 2, h * .22 + 2); fill(ctx, A(c, .22));
      nozzle(L, R, hh, h * .22); fill(ctx, c);
      rr(ctx, L + h * .5, -h * .2, w - h * .7, h * .4, h * .2); fill(ctx, grad(ctx, L, R, [[0, W(.35)], [1, W(.95)]]));
      dot(ctx, R, 0, h * .65, A(c, .55)); dot(ctx, R - h * .1, 0, h * .3, '#fff');   // 喷口发光（远看就靠这个亮点）
      for (let i = 0; i < 14; i++) {
        const ph = rng(), sp = rng() * 2 - 1, q = rng();
        const u = (t + ph) % 1, k = (1 - u) * (1 - u);        // 近亮远淡
        const r = (h * .1 + q * h * .12) * (1 - u * .4);
        dot(ctx, R + h * .4 + u * w * .45, sp * (h * .08 + u * h), r, u < .4 ? W(.95 * k) : A(c, k));
      }
    },
  });

  // 5. 扫光 —— 尾端切平、越往头越实，头端一道白光棱；一道高光从尾扫到头，到头闪一下
  window.SWIPE.push({
    name: '扫光',
    desc: '尾端切平、越往头越实，头端立一道白光棱；高光从尾扫到头，到头闪一下',
    draw(ctx, n, t) {
      face(ctx, n);
      const { w, h, color: c } = n, L = -w / 2, R = w / 2, hh = h / 2;
      const body = (L, R, hh) => {
        ctx.beginPath(); ctx.moveTo(L, -hh); ctx.lineTo(R - hh, -hh);
        ctx.arc(R - hh, 0, hh, -Math.PI / 2, Math.PI / 2); ctx.lineTo(L, hh); ctx.closePath();
      };
      body(L - 2, R + 2, hh + 2); fill(ctx, A(c, .2));
      body(L, R, hh); fill(ctx, grad(ctx, L, R, [[0, A(c, .3)], [.75, c], [1, c]]));
      rr(ctx, L + h * .2, -h * .2, w - h * .9, h * .4, h * .2); fill(ctx, grad(ctx, L, R, [[0, W(.1)], [1, W(.9)]]));
      const p = L + (w - h) * Math.min(1, t / .7), flash = t < .7 ? 0 : 1 - (t - .7) / .3;   // 扫到头再闪一下
      const a = .7 * Math.min(1, t / .1) * (1 - flash);                                         // 扫光带起止渐隐，循环处不跳
      ctx.save(); body(L, R, hh); ctx.clip();
      ctx.fillStyle = grad(ctx, p - h, p + h, [[0, W(0)], [.5, W(a)], [1, W(0)]]); ctx.fillRect(p - h, -hh, h * 2, h);
      ctx.restore();
      rr(ctx, R - h * .55, -h * .65, h * .28, h * 1.3, h * .14); fill(ctx, W(.75 + .25 * flash));   // 光棱，比本体高一点
    },
  });
})();
