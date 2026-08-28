package app.yxi.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.CancellationSignal

/**
 * 危险审批的**指纹/人脸门禁** —— 口袋误触、别人凑手机上点一下 `rm -rf` 的保险丝。
 *
 * 用**系统自带** `android.hardware.biometrics.BiometricPrompt`（API 28+），不引 androidx 依赖。
 * 没有生物识别 / 没设锁屏 / 系统太老 → **直接放行**，绝不把人挡在正常使用外面（门禁是给危险操作的，不是给全部）。
 */
object Biometric {
    fun gate(ctx: Context, subtitle: String, onOk: () -> Unit) {
        val km = ctx.getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT < 28 || km == null || !km.isDeviceSecure) { onOk(); return }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(ctx)
            .setTitle(t("确认这次操作"))
            .setSubtitle(subtitle.take(80))
            .apply {
                if (Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                ) else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
            }
            .build()
        runCatching {
            prompt.authenticate(
                CancellationSignal(), ctx.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult,
                    ) { onOk() }
                },
            )
        }.onFailure { onOk() }   // 弹不出来（个别 ROM）也别把人卡死，放行
    }
}

/** Compose 的 LocalContext 往上扒出 Activity。 */
fun Context.findActivity(): android.app.Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is android.app.Activity) return c; c = c.baseContext }
    return null
}
