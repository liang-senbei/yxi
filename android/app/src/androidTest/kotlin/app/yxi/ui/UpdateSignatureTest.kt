package app.yxi.ui

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 更新包的签名校验（交接 §7-38，用户 10-16 定的补）：Android 8.x 改走老接口后也要**真读到**两边的证书——
 * 不能是靠「读不到就放行」过的。两台模拟器（Android 16、Android 8.0）都跑。
 * 「别人签的包」用系统框架包 framework-res.apk（平台密钥签的、谁都能读，也不受 Android 11 起的包可见性限制）。
 */
@RunWith(AndroidJUnit4::class)
class UpdateSignatureTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ownApkReadsTheSameSigners() {
        val apk = File(ctx.applicationInfo.sourceDir)
        val theirs = apkSigners(ctx, apk)
        val ours = installedSigners(ctx)
        assertFalse("读不到安装包的签名（API ${Build.VERSION.SDK_INT}）", theirs.isNullOrEmpty())
        assertFalse("读不到本机的签名（API ${Build.VERSION.SDK_INT}）", ours.isNullOrEmpty())
        assertEquals(ours, theirs)
        assertNull(signatureMismatch(ctx, apk))
    }

    @Test fun apkSignedByAnotherKeyIsRejected() {
        val other = File("/system/framework/framework-res.apk").takeIf { it.canRead() } ?: error("模拟器上读不到 framework-res.apk")
        val theirs = apkSigners(ctx, other)
        assertFalse("读不到系统包的签名（API ${Build.VERSION.SDK_INT}）", theirs.isNullOrEmpty())
        assertNotEquals(installedSigners(ctx), theirs)
        assertNotNull("签名不同却没拦（API ${Build.VERSION.SDK_INT}）", signatureMismatch(ctx, other))
    }
}
