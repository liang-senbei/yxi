# Runner 品牌图标来源清单（SOURCES）

- 抓取日期：2026-09-22（全部为当次直接下载的公开资产，未使用任何凭据；未采用 LobeHub / CCSwitch 等第三方图标聚合库）。
- 文件均为官方站点或官方 GitHub 仓库发布的**原始字节**（未做任何修改、裁剪、重绘）；SHA-256 供 root 复核比对。
- 用途：配置页引擎选择 chip 的品牌指示图标（指示产品来源的图标性使用，非宣传物料）。

## claude → claude-code.png

- 来源：claude.ai（Claude 官方站点）HTML `<link rel="apple-touch-icon">` 声明的官方资产
- 原 URL：https://cdn.prod.website-files.com/6889473510b50328dbb70ae6/68c33859cc6cd903686c66a2_apple-touch-icon.png
- 内容：Claude 星芒（starburst）应用图标，256×256 PNG RGBA
- SHA-256：`1bec5f7b12a4a46fea879633464ebf1d32144ef731a0f054539b2d7251871cb6`
- 许可/品牌说明：Claude 为 Anthropic 商标；官方品牌条款页未能用无凭据请求确认（anthropic.com/brand 等候选路径 404）。官方仓库 anthropics/claude-code（commit b486776a2eef0d3f39abaebaa3c03e378c7480b8）经全树检索不含任何图形资产，故取 claude.ai 站点发布的应用图标。

## codex → codex-openai.svg

- 来源：OpenAI 官方仓库 openai/codex（Codex CLI 官方仓库）
- 固定提交：`94174e44cbc54cece45f6052328ca0c2cd7a8a2a`（2026-09-22 git ls-remote HEAD）
- 仓库内路径：`codex-rs/skills/src/assets/samples/openai-docs/assets/openai-small.svg`
- 原 URL：https://raw.githubusercontent.com/openai/codex/94174e44cbc54cece45f6052328ca0c2cd7a8a2a/codex-rs/skills/src/assets/samples/openai-docs/assets/openai-small.svg
- 内容：OpenAI 花形标记（monochrome，`fill="currentColor"`，viewBox 14×14），**代码中按前景色着色**（与原 Material 图标着色方式一致）
- SHA-256：`45be1f0757eb18889eefb1e7db79668ef46a275dc4e0e78e8df5ebd7f6cdeadc`
- 许可：仓库 LICENSE（该 commit）为 Apache-2.0
- 说明：本次已检查来源未找到独立的 "Codex" 专用图形资产（openai.com/brand 对无凭据请求返回 403 无法核实）；此为官方 Codex 仓库内随库发布的官方 OpenAI 标记，为可证实的最接近官方来源。

## opencode → opencode.png

- 来源：opencode.ai（OpenCode 官方站点）HTML `<link rel="apple-touch-icon" sizes="180x180">` 声明的官方资产
- 原 URL：https://opencode.ai/apple-touch-icon-v3.png
- 内容：OpenCode 像素块标志应用图标，180×180 PNG RGBA
- SHA-256：`2f2d5687a641f7fc1c3931072249a5984660375c091064fe8b374e4e43a8656c`
- 许可：官方仓库 opencode-ai/opencode（commit `73ee493265acf15fcd8caab2bc8cd3bd375b63cb`，2026-09-22 git ls-remote HEAD）LICENSE 为 MIT；该仓库信息仅供来源记录；站点图标本身未附独立许可条款，不能将仓库 MIT 自动套用于站点资产。

## gemini → gemini.png

- 来源：gemini.google.com（Google Gemini 官方站点）HTML `<link rel="icon" sizes="512x512">` 声明的官方资产
- 原 URL：https://www.gstatic.com/lamda/images/gemini_sparkle_4g_512_lt_f94943af3be039176192d.png
- 内容：Gemini 星芒（spark）渐变图标，512×512 PNG RGBA（透明底）
- SHA-256：`5e7cfecaa53f4f65a313fe89b0f389548126544a78fad8489510c70ae641a4a1`
- 许可/品牌说明：Gemini 为 Google 商标；品牌元素使用规范见 Google 品牌资源中心 https://about.google/brand-resource-center/brand-elements/ （2026-09-22 可公开访问，HTTP 200）。

## grok → grok-xai.svg

- 来源：docs.x.ai（xAI 官方开发者文档站）HTML `<link rel="icon" type="image/svg+xml">` 声明的官方资产（深色变体）
- 原 URL：https://docs.x.ai/_next/static/media/favicon-dark.0-f2gt9doy0_1.svg?dpl=dcac660eb5e7f39f3163814c363fe64143fef209
- 内容：xAI/Grok 标志（深色圆角方块底、白色标志），512×512 SVG
- SHA-256：`986c5af775f7b550d41cf44e20d981407b30e7f24fa231e8434c5ec3db9c5307`
- 许可/品牌说明：Grok/xAI 为 xAI 商标；x.ai 法律页面（x.ai/legal 等）对无凭据请求返回 403，未能核实条款文本。grok.com 站点资产被反爬拦截（HTTP 403），故取 xAI 官方 docs 站发布并原样声明的 favicon；同一页面声明的浅色变体为 https://docs.x.ai/_next/static/media/favicon-light.1u6watcuoe8mg.svg （未收录）。
- 渲染注意：该 SVG 使用 `<mask>` 与 mask-type:alpha，root 验证时请在两种主题下确认 Skia 渲染正常。

## hermes → hermes.png

- 来源：官方 NousResearch/hermes-agent 仓库
- 固定提交：78dfd6e6e7df5f2ddb5e90064c4a29d416acd69f
- 原 URL：https://raw.githubusercontent.com/NousResearch/hermes-agent/78dfd6e6e7df5f2ddb5e90064c4a29d416acd69f/apps/desktop/assets/icon.png
- SHA-256：`d60d164e24fdcf6532133b8ea43c77a201e4b9e9dbc396187b58d51d8590ef52`
- 仓库 MIT 许可证保存于 `licenses/hermes-agent-MIT.txt`。保留原始图形，不使用通用机器人图标代替。

## 复核指引（root）

1. 逐文件 `sha256sum` 与上表比对；原 URL 重新抓取应得到同字节（openai/codex 请在固定提交上取）。
2. 各站 HTML 中 `<link rel="icon"/apple-touch-icon">` 声明可用 `curl -A "<浏览器UA>" <站点>` 复查（grok.com 403、openai.com 403 为反爬，需浏览器环境）。
3. 许可声明总文件按约定由 root 统一处理；本清单仅记录逐资产来源与当前可证实的许可/条款状态。
