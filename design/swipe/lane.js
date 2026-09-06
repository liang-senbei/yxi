// swipe 音块左右方向标记 · 轨道 / 环境提示类（标记画在块周围的空间里）· 5 款
// 契约：draw(ctx, note, t, rng)，note={x,y,w,h,color,dir,lineY,laneX,laneW}，dir=-1 左 / +1 右，
//      t∈[0,1] 每 0.8s 一圈，rng 每帧同序列，函数内不存状态。
// 环境提示统一用 laneClip 裁在本轨道内、判定线以上，透明度 ≤ .35；本体画在裁剪外面（压线时不能被切）。
(() => {
  const hex = c => [parseInt(c.slice(1, 3), 16), parseInt(c.slice(3, 5), 16), parseInt(c.slice(5, 7), 16)];
  // k>0 向白混，k<0 向黑混
  const rgba = (c, a, k = 0) => {
    const f = k > 0 ? v => v + (255 - v) * k : v => v * (1 + k);
    return `rgba(${f(c[0]) | 0},${f(c[1]) | 0},${f(c[2]) | 0},${a < 0 ? 0 : a > 1 ? 1 : a})`;
  };
  const TAU = Math.PI * 2;
  // 药丸路径（r 默认半高）
  const pill = (ctx, x, y, w, h, r = h / 2) => {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  };
  // 尖角：顶点 (x,y) 朝 d，半高 s；只添路径不描
  const chev = (ctx, x, y, s, d) => { ctx.moveTo(x - d * s * .55, y - s); ctx.lineTo(x, y); ctx.lineTo(x - d * s * .55, y + s); };
  const laneClip = (ctx, n) => { ctx.beginPath(); ctx.rect(n.laneX, 0, n.laneW, n.lineY); ctx.clip(); };
  // 本体：圆角薄片（fill 可传渐变）+ 白芯 + 两头 ‹ ›，3 次绘制
  const body = (ctx, n, fill) => {
    const { x, y, w, h } = n, c = hex(n.color);
    ctx.shadowColor = rgba(c, .7); ctx.shadowBlur = 8;
    pill(ctx, x - w / 2, y - h / 2, w, h); ctx.fillStyle = fill || n.color; ctx.fill();
    ctx.shadowBlur = 0;
    pill(ctx, x - w / 2 + h * .35, y - h * .22, w - h * .7, h * .44); ctx.fillStyle = 'rgba(255,255,255,.85)'; ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,.95)'; ctx.lineWidth = 1.6; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.beginPath(); chev(ctx, x - w / 2 - h * .35, y, h * .5, -1); chev(ctx, x + w / 2 + h * .35, y, h * .5, 1); ctx.stroke();
  };

  window.SWIPE = window.SWIPE || [];

  // 1 ─────────────────────────────────────────────── 轨道尖角
  window.SWIPE.push({
    name: '轨道尖角',
    desc: '块下方的轨道里横铺一排淡尖角全朝方向，方向侧渐亮，一点亮光顺着尖角往方向流',
    draw(ctx, n, t) {
      const { y, h, dir: d, laneX, laneW } = n, c = hex(n.color);
      ctx.save(); laneClip(ctx, n);
      const ry = y + h * 2.9, s = h * 1.2, N = 9, step = laneW / (N + 1);
      // 垫一层极淡的渐变带：方向侧亮——缩到很小时靠它分左右
      const g = ctx.createLinearGradient(laneX, 0, laneX + laneW, 0);
      g.addColorStop(d > 0 ? 0 : 1, rgba(c, 0)); g.addColorStop(.5, rgba(c, .04)); g.addColorStop(d > 0 ? 1 : 0, rgba(c, .2));
      ctx.fillStyle = g; ctx.fillRect(laneX, ry - s, laneW, s * 2);
      ctx.lineWidth = 1.6; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      for (let i = 1; i <= N; i++) {
        const u = d > 0 ? i / (N + 1) : 1 - i / (N + 1);      // 0..1 顺方向
        const bump = Math.max(0, 1 - Math.abs(u - t) / .2);    // 一点亮光顺方向跑
        ctx.strokeStyle = rgba(c, .05 + .23 * u * u + .07 * bump, .3 * bump);
        ctx.beginPath(); chev(ctx, laneX + step * i, ry, s, d); ctx.stroke();
      }
      ctx.restore();
      body(ctx, n);
    }
  });

  // 2 ─────────────────────────────────────────────── 落点光带
  window.SWIPE.push({
    name: '落点光带',
    desc: '判定线上、块将落的位置预先亮一道朝方向收窄的光楔，一粒光顺楔往方向跑，块越近楔越亮',
    draw(ctx, n, t) {
      const { x, y, w, h, dir: d, lineY } = n, c = hex(n.color);
      const near = .55 + .45 * (y / lineY);
      const x0 = x - d * w * .3, x1 = x + d * n.laneW * .42, H = h * 2.2, base = lineY - 1;
      ctx.save(); laneClip(ctx, n);
      // 光楔：落点处最高最亮，朝方向收成尖
      const g = ctx.createLinearGradient(x0, 0, x1, 0);
      g.addColorStop(0, rgba(c, .35 * near, .15)); g.addColorStop(1, rgba(c, 0));
      ctx.fillStyle = g;
      ctx.beginPath(); ctx.moveTo(x0, base - H); ctx.lineTo(x1, base); ctx.lineTo(x0, base); ctx.closePath(); ctx.fill();
      // 楔的斜边描一道细亮线，形状才立得住
      ctx.strokeStyle = g; ctx.lineWidth = 1.2; ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(x0, base - H); ctx.lineTo(x1, base); ctx.stroke();
      // 落点：一短竖光
      ctx.fillStyle = rgba(c, .35 * near, .5); ctx.fillRect(x - 1, base - H * 1.4, 2, H * 1.4);
      // 一粒光顺楔跑
      const px = x0 + (x1 - x0) * t;
      ctx.fillStyle = rgba(c, .35 * (1 - t) * near, .6); ctx.fillRect(px - 6, base - 3, 12, 3);
      ctx.restore();
      body(ctx, n);
    }
  });

  // 3 ─────────────────────────────────────────────── 反向尾迹
  window.SWIPE.push({
    name: '反向尾迹',
    desc: '块朝反方向拖出逐级变淡的残像和细速度线，像已经在往方向冲；方向侧的头部尖角更亮更粗',
    draw(ctx, n, t, rng) {
      const { x, y, w, h, dir: d } = n, c = hex(n.color);
      const br = 1 + .12 * Math.sin(TAU * t);                  // 尾迹一伸一缩
      ctx.save(); laneClip(ctx, n);
      for (let i = 1; i <= 3; i++) {
        pill(ctx, x - d * w * .28 * i * br - w / 2, y - h / 2, w, h);
        ctx.fillStyle = rgba(c, [.22, .12, .05][i - 1], -.15); ctx.fill();
      }
      // 速度线 ×4：从尾端往反方向甩出，长短由 rng 定（每帧同序列，不闪）
      ctx.lineCap = 'round';
      const xs = x - d * w * .5;
      for (let i = 0; i < 4; i++) {
        const ly = y + (i - 1.5) * h * .55, len = w * (.6 + rng() * .6) * br;
        const g = ctx.createLinearGradient(xs, 0, xs - d * len, 0);
        g.addColorStop(0, rgba(c, .32, .3)); g.addColorStop(1, rgba(c, 0));
        ctx.strokeStyle = g; ctx.lineWidth = i % 2 ? 1 : 1.5;
        ctx.beginPath(); ctx.moveTo(xs, ly); ctx.lineTo(xs - d * len, ly); ctx.stroke();
      }
      ctx.restore();
      body(ctx, n);
      // 头部：方向侧尖角加亮加粗一档
      ctx.strokeStyle = '#fff'; ctx.lineWidth = 2.4;
      ctx.beginPath(); chev(ctx, x + d * (w / 2 + h * .35), y, h * .55, d); ctx.stroke();
    }
  });

  // 4 ─────────────────────────────────────────────── 偏光
  window.SWIPE.push({
    name: '偏光',
    desc: '方向侧的轨道被一团柔光照亮、反方向侧压暗，块身也顺着这光一头亮一头暗',
    draw(ctx, n, t) {
      const { x, y, w, dir: d } = n, c = hex(n.color);
      const R = w * 1.1, br = .27 + .08 * Math.sin(TAU * t);   // 呼吸
      ctx.save(); laneClip(ctx, n);
      // 方向侧：椭圆柔光（径向渐变压扁）
      ctx.save(); ctx.translate(x + d * w * .8, y); ctx.scale(1, .34);
      let g = ctx.createRadialGradient(0, 0, 0, 0, 0, R);
      g.addColorStop(0, rgba(c, br, .2)); g.addColorStop(1, rgba(c, 0));
      ctx.fillStyle = g; ctx.fillRect(-R, -R, 2 * R, 2 * R); ctx.restore();
      // 反方向侧：压暗
      ctx.save(); ctx.translate(x - d * w * .75, y); ctx.scale(1, .34);
      g = ctx.createRadialGradient(0, 0, 0, 0, 0, R * .9);
      g.addColorStop(0, 'rgba(0,0,0,.35)'); g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g; ctx.fillRect(-R, -R, 2 * R, 2 * R); ctx.restore();
      ctx.restore();
      // 块身：方向头亮、反方向头暗
      const bg = ctx.createLinearGradient(x - d * w / 2, 0, x + d * w / 2, 0);
      bg.addColorStop(0, rgba(c, 1, -.4)); bg.addColorStop(1, rgba(c, 1, .45));
      body(ctx, n, bg);
    }
  });

  // 5 ─────────────────────────────────────────────── 边墙
  window.SWIPE.push({
    name: '边墙',
    desc: '方向那侧的轨道边亮起一段竖光和 [ 形括角接着块，一条虚线从块牵到墙上、朝墙流动',
    draw(ctx, n, t) {
      const { x, y, w, h, dir: d, laneX, laneW } = n, c = hex(n.color);
      const wx = d > 0 ? laneX + laneW - 2 : laneX + 2;         // 墙贴轨道边，留 2px 在轨道内
      const near = .6 + .4 * (y / n.lineY), H = h * 5, gw = w * .4;
      ctx.save(); laneClip(ctx, n);
      // 竖光：贴墙一段柔光，往轨道里散开、上下淡出（径向渐变压成竖椭圆）
      ctx.save(); ctx.translate(wx, y); ctx.scale(gw / H, 1);
      const g = ctx.createRadialGradient(0, 0, 0, 0, 0, H);
      g.addColorStop(0, rgba(c, (.28 + .07 * Math.sin(TAU * t)) * near, .1)); g.addColorStop(1, rgba(c, 0));
      ctx.fillStyle = g; ctx.fillRect(-H, -H, 2 * H, 2 * H); ctx.restore();
      // 墙线 + 上下括角，开口朝块
      ctx.strokeStyle = rgba(c, .35 * near, .5); ctx.lineWidth = 2; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      const b = h * 1.3, hh = h * 2.2;
      ctx.beginPath(); ctx.moveTo(wx - d * b, y - hh); ctx.lineTo(wx, y - hh); ctx.lineTo(wx, y + hh); ctx.lineTo(wx - d * b, y + hh); ctx.stroke();
      // 虚线：块的方向端 → 墙，dashOffset 递减 = 虚线往墙流
      ctx.strokeStyle = rgba(c, .25 * near, .3); ctx.lineWidth = 1.2;
      ctx.setLineDash([3, 6]); ctx.lineDashOffset = -t * 9;
      ctx.beginPath(); ctx.moveTo(x + d * (w / 2 + h), y); ctx.lineTo(wx - d * (b + 3), y); ctx.stroke();
      ctx.setLineDash([]);
      ctx.restore();
      body(ctx, n);
    }
  });
})();
