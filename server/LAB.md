# Yxi 实验室 · 内容契约（给 agent 看的）

实验室 = 服务器上的 `~/.yxi/lab/`（manifest.json + 素材文件），手机 App 只读它。
每台服务器各自一个实验室，互不相干；**实验室内容永远不嵌进 App**。所以：想给用户看/审东西，
不改 App，只用 `yxi-lab`。

## 流程
1. 产出一个文件（网页 / 图 / GIF / 视频 / 文本）。网页先 `yxi-lab template > x.html` 拿骨架。
2. `yxi-lab check x.html` 校验；有 ✗ 就改。
3. `yxi-lab add x.html "标题" "一句说明" "由谁生成" --aspect 1:1 --group 组名`
4. 提醒用户：手机 App → 实验室 → 刷新。
5. 用户勾「采纳」后 `yxi-lab approved` 看结果；不要的 `yxi-lab rm <id>`。
6. **更新某一项**（用户在手机上点了「更新」，或你自己要替换）：`yxi-lab update <id> <新文件> "<一句：改了什么>"` —— id 不变、原位刷新。

## 手机上的三个按钮（D29）
实验室页顶上「查明并画出来」「把结构画成图」，每张卡上「更新」。点了手机把一整段任务说明发进你所在的会话
（用户可以附一句提示词，不附就是默认执行）。你照着做、用 `yxi-lab add` / `yxi-lab update` 推回来即可 —— App 里不带任何技能。
架构图默认组「架构图」、比例 16:10；普通图表组「图表」。有 archify 技能就用它，deliver 后剥掉 Google 字体引用。

## 类型与展示
| type  | 文件            | 手机上怎么显示                                   | aspect 默认 |
|-------|-----------------|--------------------------------------------------|-------------|
| html  | .html           | WebView（JS 开、离线沙箱），按 aspect 定预览框，可全屏 | 1:1 |
| image | .png .jpg .webp | 整图等比缩放，不裁；可全屏、可存相册               | 原图 |
| gif   | .gif            | 动图播放；可存相册                                | 原图 |
| video | .mp4 .webm      | 可播放；可存相册                                  | 16:9 |
| note  | .md .txt 或 note | 只显示文字                                       | —  |

预览框宽 ≈ 手机宽 − 56dp（约 330dp），高 = 宽 × aspect。全屏时铺满整屏。

## 网页（html）硬规矩
- **单文件、离线**：图片用 data URI 内联；不引用 http(s) 资源、不引用外部脚本 / 字体 / CSS（沙箱里拿不到，`check` 会拦）。
- 有 `<meta name="viewport" content="width=device-width, initial-scale=1">`；`<meta name="yxi-aspect" content="9:16">` 声明比例。
- 尺寸从 `innerWidth/innerHeight` 读并**监听 resize**（WebView 首帧可能是 0×0）；按 devicePixelRatio 设画布。
- 不依赖用户手势开始；播完停 2 秒自动重播；点一下立刻重播；底部一行「点一下重播」小字。
- ≤ 1MB。每帧别分配对象。不用 localStorage / getUserMedia / fetch。
- 允许：canvas 2D、CSS 动画、requestAnimationFrame、getImageData（同文档 data URI 图不污染画布）。

## manifest.json 一条
{"id":"a-…","title":"…","type":"html","file":"a-….html","desc":"一句话","by":"谁","at":1788340000,"aspect":"1:1","group":"开屏动效"}

## 审核
用户在手机上勾「采纳」→ 服务器 `~/.yxi/lab-approvals.txt` 里一行一个 id → `yxi-lab approved` 对着 manifest 打印标题。
