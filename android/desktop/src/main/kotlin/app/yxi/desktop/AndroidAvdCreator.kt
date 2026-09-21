package app.yxi.desktop

import java.io.File

/**
 * AndroidAvdCreator —— 在 Yxi 自有 SDK 根上创建虚拟设备（用户显式选择的系统镜像
 * + 设备规格），由 root UI 显式发起。
 *
 * ⚠️ **已有 AVD 不覆盖**：`--force` 永不使用，重名由 avdmanager 拒绝并如实上报。
 * ⚠️ avdmanager 按 cmdline-tools 安装布局自动定位 SDK（安装位 ../../.. 即 Yxi 根），
 * 不依赖环境变量。
 * ⚠️ 命令安全同 [runToolNative]：名称/镜像/规格全走白名单，不合格不跑。
 */
class AndroidAvdCreator(
    /** Yxi 自有 SDK 根（Bootstrap 首次配置、组件安装的同一处）。 */
    private val sdkRoot: File = File(Store.dir, "android-sdk"),
    /** avdmanager.bat 位置，按官方布局默认在 cmdline-tools/latest/bin；测试注假路径。 */
    private val avdmanagerBat: File = File(File(File(File(sdkRoot, "cmdline-tools"), "latest"), "bin"), "avdmanager.bat"),
    /** 工具执行器；生产走 [runToolNative]，测试注假的，零真创建。 */
    private val runTool: (File, List<String>, Long, () -> Boolean) -> ToolRun = ::runToolNative,
) {

    data class Outcome(val created: Boolean = false, val reason: String? = null) {
        val ok: Boolean get() = created
    }

    /** 创建 AVD。失败一律给可解释 reason；取消/超时在工具层有回执。 */
    fun create(
        name: String,
        systemImage: String,
        deviceProfile: String,
        isCancelled: () -> Boolean = { false },
    ): Outcome {
        if (!AVD_NAME.matches(name)) {
            return Outcome(reason = "设备名只允许字母/数字/._-（1-100 位）：${name.take(60)}")
        }
        if (!AndroidSdkComponentInstaller.PACKAGE_SPEC.matches(systemImage)) {
            return Outcome(reason = "系统镜像包名不合法（只允许字母数字._;+-）：${systemImage.take(60)}")
        }
        if (!DEVICE_PROFILE.matches(deviceProfile)) {
            return Outcome(reason = "设备规格 id 不合法：${deviceProfile.take(60)}")
        }
        if (AndroidSdkComponentInstaller.pathUnsafe(sdkRoot)) {
            return Outcome(reason = "SDK 根路径含命令行特殊字符，无法安全调用：${sdkRoot.path.take(120)}")
        }
        // 可自定义 exe 路径——只查 sdkRoot 挡不住 bat 本身的路径注入
        if (AndroidSdkComponentInstaller.pathUnsafe(avdmanagerBat)) {
            return Outcome(reason = "avdmanager 路径含命令行特殊字符，无法安全调用：${avdmanagerBat.path.take(120)}")
        }
        if (!avdmanagerBat.isFile) {
            return Outcome(reason = "找不到 avdmanager（${avdmanagerBat.path}）—— 先完成命令行工具的首次下载")
        }
        val run = runTool(
            avdmanagerBat,
            listOf("create", "avd", "-n", name, "-k", systemImage, "-d", deviceProfile),
            CREATE_TIMEOUT_SECONDS,
            isCancelled,
        )
        return when {
            run.cancelled -> Outcome(reason = "已取消")
            run.timedOut -> Outcome(reason = "创建超时（${CREATE_TIMEOUT_SECONDS}s），进程已强制结束")
            run.exitCode == 0 -> Outcome(created = true)
            run.output.contains("already exists", ignoreCase = true) ->
                Outcome(reason = "同名虚拟设备已存在 —— 换个名字或先删除旧的（--force 永不使用，已有 AVD 不覆盖）")
            else -> Outcome(reason = "创建失败（退出码 ${run.exitCode}）：${run.output.trim().lineSequence().lastOrNull()?.take(160) ?: "无输出"}")
        }
    }

    companion object {
        /** AVD 名：字母数字与 `._-`，1-100 位。 */
        val AVD_NAME = Regex("^[A-Za-z0-9._-]{1,100}$")

        /** 设备规格 id（avdmanager -d，如 pixel_7）：同款白名单。 */
        val DEVICE_PROFILE = Regex("^[A-Za-z0-9._-]{1,100}$")

        const val CREATE_TIMEOUT_SECONDS = 60L
    }
}
