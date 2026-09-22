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

## codex → codex-openai.png

- Publisher asset: OpenAI developer website, declared favicon.
- Source: https://developers.openai.com/favicon.png
- SHA-256: `8d5575ee667ff715cd3e3074d5296edc68d5aadd89720d203e13131b68b22a04`
- OpenAI knot on blue background. This is the publisher mark, not a claimed Codex-specific logo.
- Replaces the incorrectly selected openai-docs skill book icon. No repository license is claimed for this website asset.

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

## grok → grok.svg

- Publisher asset: Grok product website, declared SVG favicon.
- Source: https://grok.com/images/favicon.svg
- SHA-256: `c3db0dfaf760b702b8490c6cbefe07fd8bfe00db43cae6a0acccf768f44d6179`
- Original Grok product mark, white on black. Replaces the SpaceXAI documentation favicon whose alpha mask rendered poorly in Skia.
- Website asset; no independent open-source license claimed.

## hermes → hermes.png

- 来源：官方 NousResearch/hermes-agent 仓库
- 固定提交：78dfd6e6e7df5f2ddb5e90064c4a29d416acd69f
- 原 URL：https://raw.githubusercontent.com/NousResearch/hermes-agent/78dfd6e6e7df5f2ddb5e90064c4a29d416acd69f/apps/desktop/assets/icon.png
- SHA-256：`d60d164e24fdcf6532133b8ea43c77a201e4b9e9dbc396187b58d51d8590ef52`
- 仓库 MIT 许可证保存于 `licenses/hermes-agent-MIT.txt`。保留原始图形，不使用通用机器人图标代替。

## Verification

Verify both asset identity and actual Compose rendering; repository ownership alone does not identify the logo. All assets are bundled, with no runtime network fetch. Brand marks identify the corresponding services and do not imply endorsement.
