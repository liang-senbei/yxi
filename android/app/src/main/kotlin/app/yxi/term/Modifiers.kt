package app.yxi.term

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.connectbot.terminal.ModifierManager

/**
 * 粘滞修饰键。**termlib 只定义了 [ModifierManager] 接口，一个实现都没给**，得自己写。
 *
 * 点一下 `Ctrl` 之后，下一个从软键盘/硬键盘来的字符会带上 Ctrl，然后自动清掉 ——
 * 清是控件替我们做的：它每处理完一次输入就调 `clearTransients()`。
 *
 * ⚠️ 掩码是 **libvterm 的**（SHIFT=1 / ALT=2 / CTRL=4），
 * **不是 `android.view.KeyEvent` 的 metaState**。两套数值完全不同，混用会得到莫名其妙的字符。
 */
class StickyModifiers : ModifierManager {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)

    // ⚠️ 接口上声明的是**方法**不是属性（`fun isCtrlActive(): Boolean`），
    // 写成 `override val` 编译器会说「overrides nothing」
    override fun isCtrlActive() = ctrl
    override fun isAltActive() = alt
    override fun isShiftActive() = shift

    /** 控件每处理完一次输入就调这里 —— 所以「点一下只管一个键」是白送的。 */
    override fun clearTransients() { ctrl = false; alt = false; shift = false }
}
