# 云曦节拍 · 关卡设计手册

> 老板 2026-09-07：「把关卡的各种设计——校准线的分裂之类的技巧——全部写成一个文档，方便后面做新功能 / 做新关卡；开一个自定义接口，自己放线、自己放方块。」
> 这份是**做关卡的人**看的：谱面格式、线的动作词汇、每种效果在代码里是怎么做到的、工具怎么用、发布怎么走。玩法规则（判定窗口、计分、无线时刻）在 `rhythm-spec.md`，这里不重复。

## 0. 三条路做一张谱

| 路 | 适合 | 工具 |
|---|---|---|
| **从音频自动生成** | 普通曲子的 easy / hard | `chart_from_audio.py <id> <曲名> <音频> --bpm N` → 4 轨谱 + 编舞 + 能量，再经 `lanes.py` 铺成 12 轨 |
| **狂热谱生成** | 高难度、观赏性 | `frenzy_chart.py <id> <曲名> <音频> --bpm N` → 逐小节节奏模板 + 花样 + 多线 + 分裂线 |
| **手写（自定义接口）** | 表演关、精确编排（五六根线摆阵型） | `levelkit.py` —— 用 Python 放线、放块、写动作，`L.save()` 出 JSON |

三条路出来的都是同一种 JSON（§1），App 不区分来源。发布走 §6，**不用发 App**。

## 1. 谱面 JSON

```json
{
  "song": "count", "zh": "12345", "bpm": 195, "difficulty": "frenzy", "offset": 0, "approach": 1.15,
  "lines":  [ …第 0 条线的关键帧… ],                       // 兼容老谱；有 judges 时以 judges[0] 为准
  "judges": [ {"lines": […]}, {"from": 44.2, "to": 54.9, "lines": […]}, … ],
  "energy": {"hz": 4, "v": [0.31, 0.35, …]},               // 曲子能量包络，底光用
  "notes":  [ {"t": 4.615, "lane": 5, "type": "tick", "dur": 0, "dir": 0, "line": 0}, … ]
}
```

- `type`：`tick`（点）/ `slide`（长按，`dur` 秒，判头 + 尾两次）/ `trace`（落线时按着就算，不用点）/ `swipe`（往 `dir` 方向划：-1 左 / +1 右）。
- `lane` 0..11，线的坐标系里左→右；`line` 是 `judges` 的下标（第 0 条永远在）。
- `approach`：音符从冒头到线的秒数（easy 1.7 / hard 1.45 / frenzy 1.15）。
- **units = 音符数 + slide 数**，服务端按它校验判定序列；改了音符就要重新 publish（§6）。
- 关键帧：`{"t", "dur", "op", "from", "to", "ease"}`，`op` ∈ `rotate`（度，顺时针为正）/ `move_x` / `move_y`（屏宽 / 屏高比例，负 y 往上）/ `alpha`（0..1，闪烁 / 渐隐）；`ease` ∈ `cubicInOut` / `easeOut` / `linear`。**同一 op 的关键帧必须首尾相接**（下一条的 from == 上一条的 to），`dur: 0` = 瞬间到位。

### 1.1 舞台特效关键帧 `stage`（「癫狂」难度，老板 09-07 傍晚的 I Wanna 视频）

谱面顶层 `"stage": [{"t","dur","op","from","to","ease"}]`，op：`zoom`（整体缩放）/ `sx` `sy`（视角拉伸）/ `spin`（整体转，度）/ `shake`（抖动幅度，屏高比例）/ `flash`（白闪 0..1）/ `bg`（背景样式，`dur: 0` 硬切：0 底光；1~5 黑白网点 / 双层彩虹射线轮 / 螺旋点阵尾迹 / 纯色硬切 + 横带 / 棋盘格透视滚动；6~10 隧道 / 星海跃迁 / 边缘均衡器 / 六角蜂巢脉冲 / 波浪条纹；11~15 电路板 / 万花筒 / 极光 / 字符雨 / 复古地平线网格；16~20 樱花飘落 / 扫描线 + 故障 / 泡泡上升 / 低多边形碎片 / 涡旋。`ui/rhythm/WildBg{A,B,C,D}.kt`，`wild_chart.py` 每张谱随机挑 3~4 种）/ `notes`（音符缩放）/ `hue`（底光色相偏移）。
**判定不受影响**：镜头是画完整个场地后再套的一层变换（`RhythmScreen` 的 withTransform），手指坐标先 `unCamera` 反变换；系统动画关掉（减弱动效）时不闪、不抖。`wild_chart.py <hard 谱>` 拿 hard 谱叠一层特效生成 `chart_<id>_wild.json`（音符不变，units 同 hard）。

## 2. 线的动作词汇（每一种在 App 里怎么实现）

App 侧：场地画在**线的坐标系**里（`RhythmScreen.kt` 的 `withTransform { translate(cx,cy); rotate(deg); scale(sLane, sTravel); translate(-W/2, -judgeY) }`），所以线怎么动，音符永远垂直于线、沿法线飞来，判定完全不受影响。姿态由 `linePose()` 算（画和触摸共用一份），= 谱面关键帧 + 慢摆 / 呼吸 + 玩家甩动 + 整条线留屏内的夹紧。

| 效果 | 怎么写 | 实现要点 |
|---|---|---|
| 倾斜 / 横移 / 升降 | `rotate` / `move_x` / `move_y` 关键帧 | `Chart.poseAt(t, line)` 取每个 op 最近开始的关键帧的 `valueAt` |
| **立竖**（90°） | `rotate → 90`，同时 `move_y → -0.30` 让线心到屏中 | 立竖时轨道方向按 |sin| 压到屏高、飞行方向拉到半屏宽（`linePose` 的 sLane / sTravel），不然外侧轨在屏外 |
| **翻面**（180°） | `rotate → 180`，`move_y → -0.56` | 音符从底下往上飞；来回都走同一方向，别 0→180→0 |
| **整段旋转** | `rotate` from θ to θ+360，`ease: linear`，紧接一条 `dur: 0` 的 θ+360 → θ | 不记回下一段会倒着甩一圈（`levelkit.Line.spin` 封装了） |
| 踩拍轻沉 | `move_y` +0.02 60ms 再 340ms 回 | `Line.dip`；别落在过渡里，会打断首尾相接 |
| **分裂成两根** | 副线 `from` 时 `dur: 0` 三帧复制主线此刻姿态，再 0.5s 错开 ±22° / 上挪 0.30，结束前合回主线姿态 | `Level.split(main, at, until)`；生成器 `frenzy_chart.split_choreo` |
| **副线出现 / 消失** | `judges[k].from / to` | 进出各淡 0.4 秒（`JudgeLine.alphaAt`）；音符只能放在窗口内 |
| **闪烁** | `alpha` 关键帧 1 → 0.1 → 1 | `Line.flash`；`Chart.lineAlphaAt` = 窗口淡入淡出 × alpha 关键帧，线和它的音符 / 特效一起亮暗 |
| **阵型**（菱形 / 正方形 / 五边 / 六边） | N 条副线，每条 `rotate = 法线角 + 180`、`move_x/y = 中心 + 法线 × size` | `Level.formation(shape, at, until, size, pulse, flash_every, spin)`；+180 是让线的「上方」朝外，音符从外面飞向中心 |
| **放大缩小** | 阵型各线的 `move_x/y` 同步在 size 和 size×(1+amp) 之间来回 | `formation(pulse=(period, amp))` |
| 整个阵型转 | 各线 `rotate` 同步 linear | `formation(spin=度/秒)` |
| 慢摆 / 呼吸 | 不用写，App 自带：`sin(0.45t)·2.2°·1.7`、`sin(0.3t)·2%屏高·1.7`，各线错相位 | `linePose` |
| 玩家甩动 | 不用写，swipe / trace 打中把线往那边甩，弹簧回正 | `Live.tilt / tiltAt` |
| **整条线留屏内** | 不用写 | `linePose` 末尾：放不下就缩，端点出界就推回（老板报过大角度时外侧音符点不到） |

多线的判定：App 用**全局轨号** g = line × 12 + lane，判定 / 按住 / 闪光代码不区分线；手指按下归**离它最近的在场线**（法向距离），整个手势跟着这条线；邻轨放宽（±1）不跨线。

## 3. 音符编排的技巧

- **跟节奏**（老板：「太无规律」）：以小节为单位选一个 16 格节奏模板（`frenzy_chart.TEMPLATES`：quarter / eighth / gallop / offbeat / syncop / burst / run），同型最多连两小节；落轨按小节选形状（`shape_lanes`：跑上 / 跑下 / 之字 / 左右手 / 波浪）。手写时用 `Line.run(t0, step, lanes)` 铺一串。
- **花样**（motif）：阶梯（12 轨依次跑过一小节）/ 镜像（对称成对合拢）/ 扇形（从中间往两边开）/ 之字。每 4 小节段首一个，眼睛能读出规律。
- **和弦**：强拍上 2~3 个音符，至少隔 3 条轨（`put()` 会拒绝挨着的）。
- **swipe** 放在小节末尾（一划收尾），方向顺着前一个音符的落轨走；**trace** 放在大跳变（≥4 轨）的后一个；**slide** 放在能量持得住的强拍，按住期间同线清场。
- **多线分配**：分裂期间两根线交替接音符，和弦的伴音去副线（`assign_lines`）；阵型每条边自己接，音符从外向内飞。
- 密度参考：easy ≈ 1.5/s，hard ≈ 3.5/s，frenzy ≈ 7/s（12345，195 BPM）。

## 4. 舞台效果怎么来的（做关卡时能借用的钩子）

| 效果 | 触发 | 可调 |
|---|---|---|
| 爆点（九款随机）+ 碎裂（八款随机）| 每次命中，画在音符所在线的位置 | 特效大小（手感面板）；狂热演示在无线时刻半空提前爆（`StageState.judgeYk`） |
| 竖向校准线 | 命中，概率按档位 30/50/70/90% | `StageState.VLINE_P` |
| 底光 | 连击热度 + 命中闪 + 跳档琥珀 + **曲子能量**（`energy`） | `energy_all.py` 生成；手写谱用 `L.energy = {"hz":4,"v":[…]}` 或留空（0.6） |
| 无线时刻 | 最高档每一下 35%，一局一次，10~13 秒全屏可点 | `RhythmScreen` onJudge |
| 长按持续特效 | slide 按住期间 | `drawHoldFx` |
| 命中音 | 四种音块一块一种（tick 原声 / swipe 光尘 / trace 风铃散 / slide 樱瓣） | `RhythmSfx`，20 款备选在 `design/sfx/shatter/` |

## 5. 工具一览

| 文件 | 干什么 |
|---|---|
| `design/music/chart_from_audio.py` | 音频 → easy / hard（起音强度 + 频谱质心分轨 + 长音 / 划 / 拖配额）+ 编舞 + 能量 |
| `design/music/frenzy_chart.py` | 音频 → 狂热谱（节奏模板、花样、和弦、多线 + 分裂线、狂热编舞） |
| `design/music/choreo.py` | 主线编舞（8 / 4 小节一段：倾 / 升 / 横移 / 立竖 / 翻面） |
| `design/music/lanes.py` | 4 轨 → 12 轨铺开（同时的音符 ≥2 轨间距、连续 ≤6 轨跳） |
| `design/music/energy_all.py` | 能量包络写进谱面 |
| `design/music/levelkit.py` | **自定义关卡接口**（放线 / 放块 / 动作 / 分裂 / 阵型 / 自检） |
| `design/music/wild_chart.py` | 「癫狂」难度：hard 谱 + 舞台特效关键帧（背景硬切 / 拉伸 / 踩拍 zoom / 闪屏 / 抖动 / 音符忽大忽小 / 色相） |
| `design/music/showpiece.py` | 表演关生成器：主线节奏模板 + 能量最高两段进阵型（菱形放大缩小闪烁 / 六边形旋转）+ 分裂段 + 正方形收束；`python3 showpiece.py <id> <曲名> <音频> --bpm N --out remote/<id>` |
| `design/music/make_remote_songs.py` | 把 `incoming/*.json` 描述的新曲子批量出谱（easy + hard）到 `remote/<id>/` |
| `design/music/publish_songs.py` | 发布：攒 dist → 上传 hk13 → 先跑 logto 的 rhythm-sync 再公开清单 |
| `design/music/licenses/` | 每个来源的条款快照（商用 / 法人 / App 组込 / 署名） |

## 6. 发布一张谱 / 一首歌（不发 App）

1. 谱文件放对地方：APK 内置曲子改谱 → `android/app/src/main/assets/charts/`；清单独有的新曲子 → `design/music/remote/<id>/`（`song.json` + `<id>.ogg` + `chart_<id>_<diff>.json`，`make_remote_songs.py` 会生成）。
2. `python3 design/music/publish_songs.py`（`--dry` 只攒不传）。它会：算每张谱的 units / durationMs（曲子真实时长）→ rsync 音频 + 谱 → 清单先传成 `songs.json.new` → 在 hk13 上跑 logto 的 `rhythm-sync.py --apply`（服务端先认识新谱）→ 改名公开 → 公网真拉一遍。
3. 跑完 hub 一声 logto：把 `service/rhythm.json` 提进 git（脚本改的是线上那份）。
4. 客户端进选曲页会拉清单：谱按版本补齐；清单独有的曲子卡片上是「⤓ 下载这首」。
5. ⚠️ 加谱后「全 S 限定装扮」门槛自动 +1（判据是「每张谱都领过 S」），要维持难度得改规则（老板决定）。

## 7. 授权（一首都不能扒）

- 魔王魂：商用 / 游戏免费，署名「音楽：魔王魂」，禁流媒体发行和转卖（`licenses/maou.md`）。
- 其它来源逐站核：条款页必须明确允许 **商用 + 法人 + アプリ / ゲーム組込 + 完整使用**，署名按原话写进 `credit`（选曲页原样显示）。快照放 `licenses/<站>.md`。
- piapro 默认非商用；DOVA 对 App 有限制；Vocaloid 名曲多数不能商用；重音テト 收录进音游 = 商用要走 Crypton。

## 8. 一张表演关的写法（阵型示例）

```python
from levelkit import Level
L = Level('demo', '示例', bpm=140, seconds=100, difficulty='hard', approach=1.3)
main = L.line()
# 主线：前 16 小节铺八分 + 每 4 小节一个阶梯
for bar in range(2, 18):
    main.run(L.beat(bar), L.spb / 2, [3, 8] * 4)
    if bar % 4 == 2: main.run(L.beat(bar, 3), L.spb / 4, [0, 2, 4, 6])
main.rotate(L.beat(10), 15, 1.0).rotate(L.beat(14), -15, 1.0).rotate(L.beat(18), 0, 1.0)
# 副歌：主线淡下去，菱形阵型进场，放大缩小 + 每两拍闪一下，每条边接自己的音符
main.alpha(L.beat(18), 0.15, 0.4)
edges = L.formation('diamond', L.beat(18), L.beat(34), size=0.28, pulse=(4 * L.spb, 0.25), flash_every=8 * L.spb)
for bar in range(18, 34):
    for i, e in enumerate(edges):
        e.tap(L.beat(bar, i), 5); e.tap(L.beat(bar, i, 2), 7)
main.alpha(L.beat(34), 1.0, 0.4)
# 分裂：主线在 36~44 小节分出一根
side = L.split(main, L.beat(36), L.beat(44))
for bar in range(36, 44):
    main.tap(L.beat(bar), 4); side.tap(L.beat(bar, 2), 8)
L.save('chart_demo_hard.json')
```

写完 `python3 chart.py` 出 JSON → 放进 `remote/<id>/` 或 assets → §6 发布。
