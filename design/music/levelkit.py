"""levelkit —— 自定义关卡的接口（老板 2026-09-07：「开一个自定义接口，自己放线、自己放方块，可自定义全部」）。

用 Python 写关卡，输出 App 能直接吃的谱面 JSON（格式见 design/level-design-guide.md）。
一个最小例子：

    from levelkit import Level
    L = Level('mysong', '我的歌', bpm=128, seconds=90)
    main = L.line()                                  # 第 0 条线永远在
    main.tap(t=4.0, lane=5); main.swipe(4.5, 7, dir=1); main.slide(5.0, 3, dur=0.9); main.trace(5.5, 9, dir=1)
    main.rotate(8.0, to=90, dur=1.2).move_y(8.0, to=-0.3, dur=1.2)      # 立竖
    L.formation('diamond', at=20.0, until=32.0, size=0.28)               # 四条线摆成菱形，各自接音符
    L.save('chart_mysong_hard.json')

坐标约定（和 App 一致）：
- 轨 lane 0..11（左→右，线的坐标系里）；t 秒；dur 秒；dir -1 左 / +1 右。
- rotate 单位是度（正 = 顺时针），move_x / move_y 是屏宽 / 屏高的比例（负 y = 往上），alpha 0..1。
- 副线的 from / to 是出现 / 消失时刻（进出各淡 0.4 秒）；音符的 line 指向 L.lines 的下标。
- 规则（App 侧会按这些判定 / 服务端按 units 校验）：slide 按住期间同一条线上别放别的块；同时出现的音符至少隔 2 条轨；
  swipe 的 dir 要和标记一致（DSL 自动带）；units = 音符数 + slide 数，改了要重新 publish（publish_songs.py 会算）。
"""
import json, math

LANES = 12


class Line:
    def __init__(self, level, idx, start=None, end=None):
        self.level, self.idx = level, idx
        self.start, self.end = start, end
        self.keys = []                                 # 关键帧
        self._cur = {'rotate': 0.0, 'move_x': 0.0, 'move_y': 0.0, 'alpha': 1.0}

    # ── 音符 ──────────────────────────────────────────────────────────
    def _note(self, t, lane, kind, dur=0.0, dir=0):
        assert 0 <= lane < LANES, f'lane 越界 {lane}'
        self.level.notes.append(dict(t=round(float(t), 4), lane=int(lane), type=kind, dur=round(float(dur), 3), dir=int(dir), line=self.idx))
        return self
    def tap(self, t, lane): return self._note(t, lane, 'tick')
    def swipe(self, t, lane, dir): return self._note(t, lane, 'swipe', dir=dir)
    def trace(self, t, lane, dir): return self._note(t, lane, 'trace', dir=dir)
    def slide(self, t, lane, dur): return self._note(t, lane, 'slide', dur=dur)
    def chord(self, t, lanes):
        for l in lanes: self.tap(t, l)
        return self
    def run(self, t0, step, lanes):
        """一串音符：从 t0 起每 step 秒一个，lane 依次取 lanes（阶梯 / 之字都靠这个）"""
        for i, l in enumerate(lanes): self.tap(t0 + i * step, l)
        return self

    # ── 动作（关键帧首尾相接由 _cur 保证）────────────────────────────
    def _key(self, op, t, to, dur, ease):
        self.keys.append(dict(t=round(float(t), 3), dur=round(float(dur), 3), op=op, **{'from': round(self._cur[op], 4), 'to': round(float(to), 4)}, ease=ease))
        self._cur[op] = float(to)
        return self
    def rotate(self, t, to, dur=0.8, ease='cubicInOut'): return self._key('rotate', t, to, dur, ease)
    def spin(self, t, turns=1.0, dur=4.0):
        """匀速转 turns 圈，转完瞬间把角度记回（不然下一段会倒着甩回来）"""
        base = self._cur['rotate']
        self._key('rotate', t, base + 360.0 * turns, dur, 'linear')
        return self._key('rotate', t + dur, base, 0.0, 'linear')
    def move_x(self, t, to, dur=0.8, ease='cubicInOut'): return self._key('move_x', t, to, dur, ease)
    def move_y(self, t, to, dur=0.8, ease='cubicInOut'): return self._key('move_y', t, to, dur, ease)
    def alpha(self, t, to, dur=0.2, ease='linear'): return self._key('alpha', t, to, dur, ease)
    def set(self, t, rotate=None, move_x=None, move_y=None, alpha=None):
        """瞬间到位（dur=0）：分裂线一出场就贴在主线姿态上，用这个"""
        for op, v in (('rotate', rotate), ('move_x', move_x), ('move_y', move_y), ('alpha', alpha)):
            if v is not None: self._key(op, t, v, 0.0, 'linear')
        return self
    def flash(self, t, times=3, period=0.16):
        """闪几下（老板：「闪闪闪」）：亮 → 暗 → 亮"""
        for i in range(times):
            self.alpha(t + i * period, 0.1, period * 0.45); self.alpha(t + i * period + period * 0.5, 1.0, period * 0.45)
        return self
    def dip(self, t, amount=0.02):
        """踩拍子沉一下再回来"""
        base = self._cur['move_y']
        self._key('move_y', t, base + amount, 0.06, 'easeOut'); return self._key('move_y', t + 0.06, base, 0.34, 'cubicInOut')
    def pose_at(self, t):
        v = dict(self._cur); v.update({'rotate': 0.0, 'move_x': 0.0, 'move_y': 0.0, 'alpha': 1.0})
        for e in sorted(self.keys, key=lambda e: e['t']):
            if e['t'] > t: break
            if e['dur'] <= 0 or t >= e['t'] + e['dur']: v[e['op']] = e['to']
            else:
                k = (t - e['t']) / e['dur']
                ease = {'linear': k, 'cubicInOut': (4 * k ** 3 if k < 0.5 else 1 - ((-2 * k + 2) ** 3) / 2)}.get(e['ease'], 1 - (1 - k) ** 3)
                v[e['op']] = e['from'] + (e['to'] - e['from']) * ease
        return v


class Level:
    def __init__(self, song_id, zh, bpm, seconds, difficulty='hard', approach=1.45, offset=0.0):
        self.song_id, self.zh, self.bpm, self.seconds, self.difficulty = song_id, zh, bpm, seconds, difficulty
        self.approach, self.offset = approach, offset
        self.lines, self.notes, self.energy = [], [], None
        self.spb = 60.0 / bpm

    def line(self, start=None, end=None):
        """新开一条判定线。第一条永远在；之后的给 start / end（秒）"""
        ln = Line(self, len(self.lines), start, end); self.lines.append(ln); return ln

    def beat(self, bar, beat=0, sixteenth=0, phase=0.0):
        """小节 / 拍 / 16 分 → 秒（bar 从 0 起，4/4）"""
        return phase + (bar * 4 + beat) * self.spb + sixteenth * self.spb / 4

    # ── 阵型（老板 09-07：「五六根线变成菱形、正方形，闪闪闪、放大缩小」）────────
    def formation(self, shape, at, until, size=0.25, center=(0.0, -0.2), pulse=None, flash_every=None, spin=None):
        """摆一圈线：shape = 'diamond'（4 条，边斜 45°）/ 'square'（4 条，边横竖）/ 'hex'（6 条）/ 'penta'（5 条）/ 'tri'（3 条）。
        每条线都是一条 judge（有自己的 lanes，音符用 lines[i].tap 之类往上放；返回这些 Line）。
        size = 中心到边的距离（屏高比例）；pulse=(period, amp) 每 period 秒放大缩小 amp；flash_every = 每隔多少秒闪一下；spin = 整个阵型每秒转多少度。"""
        n = {'tri': 3, 'diamond': 4, 'square': 4, 'penta': 5, 'hex': 6}[shape]
        base_deg = 45.0 if shape == 'diamond' else 0.0
        out = []
        for i in range(n):
            ang = base_deg + 360.0 * i / n                     # 这条边的外法线方向（屏幕坐标：0 = 朝上）
            rad = math.radians(ang)
            ln = self.line(at, until)
            # 边的位置 = 中心 + 法线 × size；边的朝向 = 法线 + 90°；线的「上方」朝阵型中心 → 音符从中心外飞进来
            dx, dy = center[0] + math.sin(rad) * size * 0.5625, center[1] - math.cos(rad) * size   # 0.5625 = 9/16 把屏高比例换成屏宽比例
            ln.set(at, rotate=ang + 180.0, move_x=dx, move_y=dy, alpha=1.0)   # +180：线的「上方」（音符来向）指向阵型外侧 → 音符从外面飞向中心
            ln.alpha(at, 0.0, 0.0); ln.alpha(at, 1.0, 0.35)                  # 出场淡入
            t = at
            if pulse:
                period, amp = pulse
                while t + period < until:
                    s2 = size * (1 + amp)
                    ln.move_x(t, center[0] + math.sin(rad) * s2 * 0.5625, period / 2); ln.move_y(t, center[1] - math.cos(rad) * s2, period / 2)
                    ln.move_x(t + period / 2, dx, period / 2); ln.move_y(t + period / 2, dy, period / 2)
                    t += period
            if flash_every:
                tt = at + flash_every
                while tt < until - 0.6:
                    ln.flash(tt); tt += flash_every
            if spin:
                dur = until - at
                ln._key('rotate', at, ang + 180.0 + spin * dur, dur, 'linear')
            out.append(ln)
        return out

    def split(self, main, at, until, deg=22.0, rise=0.30):
        """从 main 线分裂出一条副线（老板 09-07：「线突然分裂复制成两根」）：出场贴在 main 的姿态上，0.5 秒内错开，结束前合回去"""
        p0 = main.pose_at(at); p1 = main.pose_at(until)
        ln = self.line(at - 0.4, until + 0.4)
        ln.set(at - 0.4, rotate=p0['rotate'], move_x=p0['move_x'], move_y=p0['move_y'])
        ln.rotate(at - 0.15, p0['rotate'] + deg, 0.5).move_y(at - 0.15, p0['move_y'] - rise, 0.5)
        ln.rotate(until - 0.3, p1['rotate'], 0.5).move_x(until - 0.3, p1['move_x'], 0.5).move_y(until - 0.3, p1['move_y'], 0.5)
        return ln

    # ── 输出 ──────────────────────────────────────────────────────────
    def check(self):
        """写完自检：同一条线同时的音符至少隔 2 轨；slide 期间同线没别的块；关键帧首尾相接"""
        by = {}
        for n in self.notes: by.setdefault((n['line'], round(n['t'], 3)), []).append(n['lane'])
        for (ln, t), lanes in by.items():
            lanes.sort()
            for a, b in zip(lanes, lanes[1:]):
                assert b - a >= 2, f'线 {ln} 在 {t}s 有两块挨着（轨 {a} / {b}）'
        for s in self.notes:
            if s['type'] != 'slide': continue
            for n in self.notes:
                if n is not s and n['line'] == s['line'] and s['t'] < n['t'] < s['t'] + s['dur'] + 0.15:
                    raise AssertionError(f"线 {s['line']} 的 slide {s['t']}s 按住期间有别的块 {n['t']}s")
        for ln in self.lines:
            for op in ('rotate', 'move_x', 'move_y', 'alpha'):
                seq = [e for e in sorted(ln.keys, key=lambda e: e['t']) if e['op'] == op]
                for a, b in zip(seq, seq[1:]):
                    assert abs(a['to'] - b['from']) < 1e-6, f'线 {ln.idx} 的 {op} 关键帧不接：{a} → {b}'
        return self

    def to_dict(self):
        self.check()
        notes = sorted(self.notes, key=lambda n: (n['t'], n['line'], n['lane']))
        judges = []
        for ln in self.lines:
            j = dict(lines=sorted(ln.keys, key=lambda e: (e['t'], e['op'])))
            if ln.start is not None: j['from'] = round(ln.start, 3)
            if ln.end is not None: j['to'] = round(ln.end, 3)
            judges.append(j)
        d = dict(song=self.song_id, zh=self.zh, bpm=self.bpm, difficulty=self.difficulty, offset=self.offset, approach=self.approach,
                 lines=judges[0]['lines'], judges=judges, notes=notes)
        if self.energy: d['energy'] = self.energy
        return d

    def save(self, path):
        json.dump(self.to_dict(), open(path, 'w'), ensure_ascii=False, separators=(',', ':'))
        n = len(self.notes); s = sum(1 for x in self.notes if x['type'] == 'slide')
        print(f'{path}: {len(self.lines)} 条线 {n} 个音符 units={n + s}')
        return path


if __name__ == '__main__':
    L = Level('selftest', '自检', bpm=120, seconds=60)
    m = L.line()
    m.run(2.0, 0.25, [0, 2, 4, 6, 8, 10]).chord(4.0, [2, 9]).slide(5.0, 5, 0.8).swipe(6.0, 7, 1).trace(6.5, 1, -1)
    m.rotate(8.0, 90, 1.0).move_y(8.0, -0.3, 1.0).spin(12.0, 1, 4.0).flash(17.0).dip(18.0)
    s = L.split(m, 20.0, 26.0); s.tap(22.0, 6)
    for i, ln in enumerate(L.formation('diamond', 30.0, 40.0, size=0.28, pulse=(2.0, 0.3), flash_every=2.0)):
        ln.tap(32.0 + i * 0.5, 5)
    d = L.to_dict()
    assert len(d['judges']) == 6 and d['judges'][2]['from'] == 30.0
    print('levelkit 自检 OK：', len(d['notes']), '音符', len(d['judges']), '条线')
