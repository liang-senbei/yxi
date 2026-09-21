package app.yxi.desktop

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.flavours.gfm.lexer._GFMLexer
import org.intellij.markdown.lexer.GeneratedLexer
import org.intellij.markdown.lexer.MarkdownLexer

/** 聊天与 Codex 消息渲染共用的 Markdown flavour（AssistantBody / CodexWorkspacePane 两处同源）。 */
internal val cjkGfmFlavour: CjkAwareGfmFlavourDescriptor by lazy { CjkAwareGfmFlavourDescriptor() }

/**
 * GFM 变体：裸链接（GFM_AUTOLINK token）内出现首个非 URL 字符（中文/全角标点/emoji）即截断，其后文本回正文；
 * 反引号紧邻的裸链接整体降级为纯文本。lexer 只会剪 ASCII 尾标点，「https://x。后续」会整体成链导致点击打开无效 URL；
 * 行内码里的裸链接同理不该可点。在 inline lexer 层拆 token：截出的尾巴存入 pending、下次 advance 以 TEXT 吐出、
 * 交给默认管线渲染为正文。不改写 Markdown 原文——已有 [文本](…) 显式链接的 href（取 LINK_DESTINATION 整段）、
 * 围栏代码块、ASCII 尾标点都不受影响。
 */
internal class CjkAwareGfmFlavourDescriptor : GFMFlavourDescriptor() {
    override fun createInlinesLexer(): MarkdownLexer = MarkdownLexer(CjkTailLexer(_GFMLexer()))
}

/**
 * URL 合法字符（RFC unreserved + reserved + %）：裸链接 token 从起点扫到第一个非法字符即截断——
 * CJK 字符/全角标点/emoji 都不是合法 URL 字符，「https://x。后续」在句号前切开，尾巴整体回正文。
 */
private val URL_SAFE_CHARS =
    ("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&*+,;=%'()").toSet()

/**
 * GeneratedLexer 代理（MarkdownLexer 只经它的 5 个成员取流，tokenEnd 先读后推进）：对 GFM_AUTOLINK
 * token 从起点扫描首个非 URL 字符截断——当前 token 截短上报，余下区间在下次 advance 以 TEXT 吐出。
 */
private class CjkTailLexer(private val delegate: GeneratedLexer) : GeneratedLexer {
    private var text: CharSequence = ""
    private var current: Pair<Int, Int>? = null   // 当前 token 的替代区间 (start, endExclusive)
    private var pending: Pair<Int, Int>? = null   // 待吐出的尾巴 TEXT token

    override fun reset(buffer: CharSequence, start: Int, end: Int, initialState: Int) {
        delegate.reset(buffer, start, end, initialState)
        text = buffer
        current = null
        pending = null
    }

    override fun advance(): IElementType? {
        val tail = pending
        if (tail != null) {
            pending = null
            current = tail
            return MarkdownTokenTypes.TEXT
        }
        current = null
        val type = delegate.advance()
        if (type == GFMTokenTypes.GFM_AUTOLINK) {
            val start = delegate.tokenStart
            val end = delegate.tokenEnd
            if ((start > 0 && text[start - 1] == '`') || (end < text.length && text[end] == '`')) {
                current = start to end   // 行内码紧邻：整体降级纯文本，反引号解析器照常成 span
                return MarkdownTokenTypes.TEXT
            }
            var cut = end
            for (i in start until end) {
                if (text[i] !in URL_SAFE_CHARS) { cut = i; break }
            }
            if (cut < end) {
                current = start to cut
                pending = cut to end
            }
        }
        return type
    }

    override val tokenStart: Int get() = current?.first ?: delegate.tokenStart
    override val tokenEnd: Int get() = current?.second ?: delegate.tokenEnd
    override val state: Int get() = delegate.state
}
