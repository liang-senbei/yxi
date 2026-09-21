package app.yxi.desktop

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AndroidDeviceBridge 定向测试：run 执行器注入假结果（记录参数列表、零真进程、零真设备），
 * APK/SDK 一律临时目录空文件。覆盖：枚举状态区分、serial/APK/包名的进程前拦截、
 * ADB 失败与安装 Failure 的区分、adb 路径解析。
 */
class AndroidDeviceBridgeTest {
    private class RecordedRun(var result: AndroidDeviceBridge.AdbRun) {
        val calls = mutableListOf<List<String>>()
        val exec: (List<String>) -> AndroidDeviceBridge.AdbRun = { args ->
            calls += args
            result
        }
    }

    private fun ok(stdout: String) = AndroidDeviceBridge.AdbRun(0, stdout, "")
    private fun tempApk(name: String = "app.apk", dir: Boolean = false): File {
        val f = Files.createTempDirectory("yxi-apk").resolve(name).toFile()
        if (dir) f.mkdirs() else f.createNewFile()
        return f
    }

    private fun envOf(vararg pairs: Pair<String, String>) = { name: String -> pairs.toMap()[name] }

    private val adb = "/fake/platform-tools/adb.exe"

    @Test
    fun `devices parses three states and skips daemon noise with bare arg list`() = runBlocking {
        val rec = RecordedRun(ok(
            "* daemon not running; starting now at tcp:5037\n" +
            "List of devices attached\n" +
            "emulator-5554\tdevice product:sdk_gphone64 model:Pixel_8 device:emu64xa transport_id:1\n" +
            "127.0.0.1:5555\toffline\n" +
            "CSX0217\tunauthorized usb:1234\n"))
        val r = AndroidDeviceBridge(run = rec.exec).devices(adbPath = adb)
        assertEquals(null, r.error)
        assertEquals(3, r.devices.size)
        assertTrue(r.devices[0].online && !r.devices[0].offline && !r.devices[0].unauthorized)
        assertEquals("Pixel_8", r.devices[0].model)
        assertTrue(r.devices[1].offline)
        assertTrue(r.devices[2].unauthorized)
        // 枚举就是一条 `adb devices -l`：参数列表、不带任何 serial——不存在「默认挑第一台」的路径
        assertEquals(listOf(adb, "devices", "-l"), rec.calls.single())
    }

    @Test
    fun `devices reports adb failure as error not as empty success`() = runBlocking {
        val timeout = AndroidDeviceBridge(run = { AndroidDeviceBridge.AdbRun(null, "", "", "adb devices 超时（10s），已强制结束") })
            .devices(adbPath = adb)
        assertTrue(timeout.devices.isEmpty())
        assertTrue(timeout.error!!.contains("超时"))
        val exited = AndroidDeviceBridge(run = { AndroidDeviceBridge.AdbRun(1, "", "error: no devices/emulators found") })
            .devices(adbPath = adb)
        assertTrue(exited.devices.isEmpty())
        assertTrue(exited.error!!.contains("退出码 1"))
    }

    @Test
    fun `install refuses bad serial and non apk before any process runs`() = runBlocking {
        val rec = RecordedRun(ok("Success"))
        val bridge = AndroidDeviceBridge(run = rec.exec)
        val dir = tempApk(name = "not-really.apk", dir = true)   // 目录冒充 APK
        listOf(
            bridge.install(adbPath = adb, serial = "  ", apk = tempApk()),
            bridge.install(adbPath = adb, serial = "emulator-5554", apk = File("/no/such/app.apk")),
            bridge.install(adbPath = adb, serial = "emulator-5554", apk = dir),
            bridge.install(adbPath = adb, serial = "emulator-5554", apk = tempApk(name = "app.ipa")),
        ).forEach { assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.InvalidRequest, it.kind, it.detail) }
        assertTrue(rec.calls.isEmpty())   // 全是进程前拦截：一次 adb 都没起
        val apk = tempApk()
        val good = bridge.install(adbPath = adb, serial = "emulator-5554", apk = apk)
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.Done, good.kind)
        assertEquals(listOf(adb, "-s", "emulator-5554", "install", apk.absolutePath), rec.calls.single())
    }

    @Test
    fun `install output failure is command failure while exit code is adb failure`() = runBlocking {
        // 退出码 0 但输出 Failure 行：包管理器的裁决 → CommandFailure，不是 ADB 的锅
        val refused = AndroidDeviceBridge(run = { ok("Performing Streamed Install\nFailure [INSTALL_FAILED_NO_SPACE]") })
            .install(adbPath = adb, serial = "s1", apk = tempApk())
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.CommandFailure, refused.kind)
        assertTrue(refused.detail.contains("INSTALL_FAILED_NO_SPACE"))
        // 退出码 1、无 Failure 行：ADB/传输层的问题 → AdbFailure
        val adbErr = AndroidDeviceBridge(run = { AndroidDeviceBridge.AdbRun(1, "", "error: device offline") })
            .install(adbPath = adb, serial = "s1", apk = tempApk())
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.AdbFailure, adbErr.kind)
        assertTrue(adbErr.detail.contains("device offline"))
        // 跑完但没有 Success 标记：诚实未知，按 AdbFailure 报，不假成功
        val silent = AndroidDeviceBridge(run = { ok("") }).install(adbPath = adb, serial = "s1", apk = tempApk())
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.AdbFailure, silent.kind)
        assertTrue(silent.detail.contains("确认不了结果"))
    }

    @Test
    fun `launch validates package name and reports monkey abort`() = runBlocking {
        val rec = RecordedRun(ok("Events injected: 1"))
        val bridge = AndroidDeviceBridge(run = rec.exec)
        listOf("-p hack", "", "com.e xample").forEach { pkg ->
            assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.InvalidRequest, bridge.launch(adbPath = adb, serial = "s1", packageName = pkg).kind)
        }
        assertTrue(rec.calls.isEmpty())   // 包名不合规：进程不起
        val good = bridge.launch(adbPath = adb, serial = "emulator-5554", packageName = "com.example.app")
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.Done, good.kind)
        assertEquals(listOf(adb, "-s", "emulator-5554", "shell", "monkey", "-p", "com.example.app",
            "-c", "android.intent.category.LAUNCHER", "1"), rec.calls.single())
        val aborted = AndroidDeviceBridge(run = { ok("** Error: Unable to find app for monkey abort") })
            .launch(adbPath = adb, serial = "s1", packageName = "com.example.app")
        assertEquals(AndroidDeviceBridge.AdbOutcome.Kind.AdbFailure, aborted.kind) // 无 Events injected 标记 → 诚实未知
        val refused = AndroidDeviceBridge(run = { ok("monkey aborted") })
            .launch(adbPath = adb, serial = "s1", packageName = "com.example.app")
        assertFalse(refused.ok)
    }

    @Test
    fun `resolve adb prefers managed root then env sdk and reports checked paths on miss`() {
        val managed = Files.createTempDirectory("yxi-managed").toFile()
            .resolve("platform-tools").apply { mkdirs() }.resolve("adb.exe").apply { createNewFile() }
        val found = AndroidDeviceBridge(managedSdkRoot = managed.parentFile.parentFile).resolveAdb()
        assertEquals(managed.absolutePath, found.getOrThrow())
        val viaEnv = Files.createTempDirectory("yxi-sdk").toFile()
            .resolve("platform-tools").apply { mkdirs() }.resolve("adb.exe").apply { createNewFile() }
        assertEquals(viaEnv.absolutePath,
            AndroidDeviceBridge(env = envOf("ANDROID_HOME" to viaEnv.parentFile.parentFile.absolutePath)).resolveAdb().getOrThrow())
        val miss = AndroidDeviceBridge(env = envOf("ANDROID_HOME" to "/no/such/sdk")).resolveAdb()
        assertTrue(miss.isFailure)
        assertTrue(miss.exceptionOrNull()!!.message!!.contains("查过"))
    }
}
