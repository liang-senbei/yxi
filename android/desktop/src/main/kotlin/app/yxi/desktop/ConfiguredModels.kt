package app.yxi.desktop

import app.yxi.ssh.Shell
import app.yxi.ssh.SshSession
import org.json.JSONObject

/** Model IDs come from this project's configuration, never another saved provider. */
internal object ConfiguredModels {
    private val aliases = setOf("default", "opus", "sonnet", "haiku", "fable", "best", "opusplan")
    private val suffix = Regex("\\[1m]$", RegexOption.IGNORE_CASE)
    fun concrete(value: String): Boolean = value.isNotBlank() && suffix.replace(value.trim(), "").lowercase() !in aliases
    data class Selection(val models: List<String>, val resolvedCurrent: String?)
    fun parse(raw: String, current: String): Selection {
        val data = JSONObject(raw)
        val env = data.optJSONObject("env") ?: JSONObject()
        val names = listOf("ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "ANTHROPIC_DEFAULT_FABLE_MODEL")
        fun resolve(value: String, visited: Set<String> = emptySet()): String? {
            val trimmed = value.trim()
            if (concrete(trimmed)) return trimmed
            val alias = suffix.replace(trimmed, "").lowercase()
            if (alias in visited) return null
            val mapped = when (alias) {
                "default" -> env.optString("ANTHROPIC_MODEL").ifBlank { data.optString("model") }
                "opus", "sonnet", "haiku", "fable" -> env.optString("ANTHROPIC_DEFAULT_${alias.uppercase()}_MODEL")
                else -> ""
            }
            if (mapped.isBlank()) return null
            // Context suffixes must be explicitly configured, not generated from an alias.
            return resolve(mapped, visited + alias)
        }
        val mapped = names.mapNotNull { resolve(env.optString(it)) }.distinct()
        val available = data.optJSONArray("availableModels")
        val explicit = (0 until (available?.length() ?: 0)).mapNotNull { resolve(available!!.optString(it)) }
        val configured = (mapped + listOfNotNull(resolve(data.optString("model")))).distinct()
        val currentId = resolve(current)
        val thirdParty = data.optBoolean("thirdParty") || env.optString("ANTHROPIC_BASE_URL").let { it.isNotBlank() &&
            runCatching { java.net.URI(it).host }.getOrNull() != "api.anthropic.com" }
        val result = if (thirdParty && configured.isNotEmpty()) configured else (configured + explicit + listOfNotNull(currentId)).distinct()
        return Selection(result, currentId)
    }
    suspend fun load(ssh: SshSession, cwd: String, current: String, session: app.yxi.agent.Session? = null): Selection {
        if (session != null) ConversationRouteApply.currentSettings(ssh, session)?.let { return parse(it, current) }
        val script = """
import json,os,sys
paths=[os.path.expanduser('~/.claude/settings.json')]
if os.path.isabs(sys.argv[1]):
 paths += [os.path.join(sys.argv[1],'.claude',n) for n in ('settings.json','settings.local.json')]
result={}; env={}
allowed={'ANTHROPIC_BASE_URL','ANTHROPIC_MODEL','ANTHROPIC_DEFAULT_OPUS_MODEL','ANTHROPIC_DEFAULT_SONNET_MODEL','ANTHROPIC_DEFAULT_HAIKU_MODEL','ANTHROPIC_DEFAULT_FABLE_MODEL'}
for path in paths:
 if not os.path.isfile(path): continue
 with open(path,encoding='utf-8-sig') as f: value=json.load(f)
 if not isinstance(value,dict): raise ValueError('Invalid settings')
 for k in ('model','availableModels'):
  if k in value: result[k]=value[k]
 source=value.get('env',{})
 if isinstance(source,dict): env.update({k:v for k,v in source.items() if k in allowed and isinstance(v,str)})
result['env']=env
print(json.dumps(result))
""".trimIndent()
        return parse(ssh.exec("python3 -c ${Shell.q(script)} ${Shell.q(cwd)}"), current)
    }
}
