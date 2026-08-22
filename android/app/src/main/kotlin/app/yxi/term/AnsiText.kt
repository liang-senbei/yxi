package app.yxi.term

/**
 * ⚠️ 临时件：把 ANSI 字节流压成人能读的纯文本，只为 G2 验证 SSH+PTY 链路通不通。
 * G2 收尾会换成真正的终端控件（带 VT 状态机、光标、滚动、选区）——那才处理得了
 * 光标定位、清屏、滚动区这些真实终端行为。**这个文件到时候整个删掉。**
 */
object AnsiText {
    private const val ESC = "\u001B"
    private val CSI = Regex(ESC + "\\[[0-9;?]*[a-zA-Z]")
    private val OSC = Regex(ESC + "\\][^" + ESC + "\\u0007]*(\\u0007|" + ESC + "\\\\)")
    private val TWO = Regex(ESC + "[()#][0-9A-Za-z]|" + ESC + "[=><]")

    fun strip(s: String): String = s
        .replace(OSC, "").replace(CSI, "").replace(TWO, "")
        .replace("\r\n", "\n").replace('\r', '\n')
}
