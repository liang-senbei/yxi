import SwiftUI
import UIKit          // UIPasteboard（一键复制代码块）
import MarkdownUI

// ⚠️ 依赖：`swift-markdown-ui`（gonzalezreal），Package.swift 要加：
//   .package(url: "https://github.com/gonzalezreal/swift-markdown-ui", from: "2.4.1")
//   target 依赖：.product(name: "MarkdownUI", package: "swift-markdown-ui")
// 平台最低 **iOS 15**（它 Package.swift 里写的）。
//
// 下面用到的 API 全部**照它 main 分支的源码核对过**：
//   `Theme()` + `.text{} .code{} .strong{} .link{}`（TextStyle 构建器）
//   `.heading1 { config in config.label … }`（BlockStyle<BlockConfiguration>）
//   `.codeBlock { config in … }` —— `config.content` / `config.language` / `config.label`
//   `.markdownTextStyle {}` / `.markdownMargin(top:bottom:)`（收 `RelativeSize`，`.em` 合法）
//   `FontSize(CGFloat)` 绝对值 / `FontSize(.em(x))` 相对值 —— 两个 init 都在

extension Theme {
    /// 聊天里 markdown 的排版。**主要为一件事存在：把标题压回正常大小。**
    ///
    /// ⚠️ Android 上这是踩出来的坑：那个库默认把 `#`/`##` 映射到 M3 的 `display*`
    /// （57sp / 45sp），而正文才 16sp —— 一个 `##` 就占掉半屏，正文反而像注脚。
    /// 用户原话：「这几个字为什么要特别大」。
    ///
    /// MarkdownUI 没那么夸张，但**方向一样，而且它就是默认值**：核对过 `Theme+Basic.swift`，
    /// `.basic` 的 h1 是 `FontSize(.em(2))`、h2 `.em(1.5)`，正文 16pt 的话 h1 就是 32pt。
    /// 落地页那么排是对的，聊天气泡里不是。
    ///
    /// **手机上标题的职责只是分段**，比正文大一点、粗一点就够。
    /// 这里和 Android 对齐：h1 20 / h2 18 / h3 16，正文 15（PRD 附录 J.1 的字号表）。
    /// 层级看得出来，但不喧宾夺主。
    static let yxi = Theme()
        .text {
            ForegroundColor(Yx.onSurface)
            FontSize(15)
        }
        .link {
            ForegroundColor(Yx.copper)
        }
        .strong {
            FontWeight(.semibold)
            ForegroundColor(Yx.onSurface)
        }
        .heading1 { c in
            c.label
                .markdownTextStyle { FontSize(20); FontWeight(.bold) }
                .markdownMargin(top: .em(0.8), bottom: .em(0.3))
        }
        .heading2 { c in
            c.label
                .markdownTextStyle { FontSize(18); FontWeight(.bold) }
                .markdownMargin(top: .em(0.7), bottom: .em(0.3))
        }
        .heading3 { c in
            c.label
                .markdownTextStyle { FontSize(16); FontWeight(.semibold) }
                .markdownMargin(top: .em(0.6), bottom: .em(0.25))
        }
        // h4 以下在聊天里基本用不到。**统一成「粗一点的正文」，别再往下缩** ——
        // 缩到比正文小就不是标题了，读起来像脚注
        .heading4 { c in c.label.markdownTextStyle { FontSize(15); FontWeight(.semibold) } }
        .heading5 { c in c.label.markdownTextStyle { FontSize(15); FontWeight(.semibold) } }
        .heading6 { c in c.label.markdownTextStyle { FontSize(15); FontWeight(.semibold) } }
        .code {
            FontFamilyVariant(.monospaced)
            FontSize(13)
            ForegroundColor(Yx.onSurfaceVar)
            BackgroundColor(Yx.high)
        }
        .codeBlock { c in
            CodeBlock(code: c.content, language: c.language)
                .markdownMargin(top: .em(0.5), bottom: .em(0.5))
        }
        .blockquote { c in
            HStack(spacing: 10) {
                Rectangle().fill(Yx.copperBox).frame(width: 3)
                c.label.markdownTextStyle { ForegroundColor(Yx.muted) }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        // ⚠️ **宽表要能横滑，聊天里和文件模式里都要。** 病根跟安卓一样
        // （`MarkdownTable.kt`）：库默认把表格塞进屏宽，列一多每格就剩几个字，
        // 用户原话「表格里全是省略号」。
        // ⚠️ 原来只在文件模式那份主题里修了，**聊天里还是挤的** —— 同一个毛病
        // 修一半比不修更难发现。挪进 `.yxi` 本体，两边一起好。
.table { c in
            ScrollView(.horizontal, showsIndicators: false) { c.label }
                // ⚠️ 不加这句，横滑容器在竖滚里会去抢一整屏高度（嵌套 ScrollView 的老毛病）
                .fixedSize(horizontal: false, vertical: true)
        }
        .tableCell { c in
            c.label
                // ⚠️ **必须是定宽 `width`，不能写 `maxWidth`。** 横滑给下来的宽度提案是「不限」，
                // `maxWidth` 只裁不换行 —— 长内容会被截掉，正是我们要修的那个毛病。
                // 定宽才会换行：窄表原样看全，宽表左右滑，一个字都不截。
                .frame(width: 160, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .markdownTextStyle { FontWeight(c.row == 0 ? .semibold : .regular) }
                // 表头单独一层底色。横滑里画横线会算歪，用面分隔（M3 那套）
                .background(c.row == 0 ? Yx.high : Color.clear)
        }
}

/// 代码块：**横着滚，不折行** + 一键复制（PRD 附录 D.2）。
///
/// ⚠️ 折行会把长管道和长路径拆得没法读 —— 终端里它们本来就是一行。
/// 宁可让用户横着滑，也别自作主张断行。
///
/// 语法高亮**没做**：MarkdownUI 要一个 `CodeSyntaxHighlighter`，
/// 而现成的（Splash / Highlightr）都是新依赖。等有人真嫌看不清再加。
private struct CodeBlock: View {
    let code: String
    let language: String?
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language?.isEmpty == false ? language! : "code")
                    .font(.mono(11))
                    .foregroundStyle(Yx.dim)
                Spacer()
                Button {
                    UIPasteboard.general.string = code
                    copied = true
                } label: {
                    Text(copied ? "已复制" : "复制")
                        .font(.mono(11))
                        .foregroundStyle(copied ? Yx.teal : Yx.muted)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 12)
            .padding(.top, 9)

            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.mono(13))
                    .foregroundStyle(Yx.onSurfaceVar)
                    .textSelection(.enabled)
                    .padding(12)
            }
        }
        .background(Yx.lowest, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}
