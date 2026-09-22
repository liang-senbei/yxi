package app.yxi.agent

import org.json.JSONObject

/** Recognize the observed native envelope, never just a user-visible phrase. */
object NativeControlMessages {
    const val INTERRUPTION = "[Request interrupted by user]"
    /** Used by remote read-only inspectors so their record classification cannot drift. */
    val pythonInterruptionFunction = """
def yxi_native_interruption(item,expected_session):
 import re
 parent=item.get('parentUuid'); session=item.get('sessionId')
 if not isinstance(parent,str) or not parent.strip() or not isinstance(session,str): return False
 if not re.fullmatch('[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}',session): return False
 if session!=expected_session or item.get('session_id')!=session or item.get('type')!='user': return False
 if item.get('entrypoint')!='cli' or item.get('version')!='2.1.278': return False
 if any(k in item for k in ('origin','promptSource','turnOrigin')): return False
 message=item.get('message'); content=message.get('content') if isinstance(message,dict) else None
 return isinstance(content,list) and len(content)==1 and isinstance(content[0],dict) and content[0].get('type')=='text' and content[0].get('text')=='$INTERRUPTION'
""".trimIndent()
    fun isInterruption(record: JSONObject): Boolean {
        val parent = record.opt("parentUuid") as? String ?: return false
        val session = record.optString("sessionId")
        if (parent.isBlank() || !Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(session)) return false
        if (record.optString("type") != "user" || record.optString("session_id") != session ||
            record.optString("entrypoint") != "cli" || record.optString("version") != "2.1.278") return false
        if (listOf("origin", "promptSource", "turnOrigin").any(record::has)) return false
        val content = record.optJSONObject("message")?.optJSONArray("content") ?: return false
        if (content.length() != 1) return false
        val block = content.optJSONObject(0) ?: return false
        return block.optString("type") == "text" && block.optString("text") == INTERRUPTION
    }
}
