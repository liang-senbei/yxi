package app.yxi.desktop

import org.json.JSONObject
import java.io.File

internal class AcpTerminalAuthPlan(val command: List<String>, val directory: String, val environment: Map<String, String>)

/** Descriptors may append arguments and override environment, never substitute another executable. */
internal fun acpTerminalAuthPlan(runtime: LocalRuntimeInstallation, directory: File, method: JSONObject,
    inherited: Map<String, String> = System.getenv()): AcpTerminalAuthPlan {
    require(runtime.ready && directory.isAbsolute && directory.isDirectory)
    require(method.getString("type") == "terminal" && !method.has("command"))
    val args = method.optJSONArray("args") ?: org.json.JSONArray()
    require(args.length() <= 128)
    val arguments = (0 until args.length()).map { args.getString(it).also { arg -> require(arg.length <= 16384 && '\u0000' !in arg) } }
    val env = AcpLaunch.environment(runtime, inherited).toMutableMap()
    val overrides = method.optJSONObject("env") ?: JSONObject()
    require(overrides.length() <= 128)
    overrides.keys().forEach { key ->
        require(Regex("[A-Za-z_][A-Za-z0-9_]{0,127}").matches(key))
        val value = overrides.getString(key)
        require(value.length <= 16384 && '\u0000' !in value)
        env[key] = value
    }
    val command = runtime.command + AcpLaunch.arguments(runtime.engine) + arguments
    require(command.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 } <= 65536)
    return AcpTerminalAuthPlan(command, directory.canonicalPath, env.toMap())
}
