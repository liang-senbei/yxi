package app.yxi.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Keep answers with the pending request across scrolling/panel switches, never on disk. */
@Composable
internal fun CodexQuestionForm(controller: CodexTaskController, request: JSONObject) {
    val params = request.optJSONObject("params") ?: JSONObject()
    val source = params.optJSONArray("questions") ?: JSONArray()
    val questions = (0 until source.length()).mapNotNull { source.optJSONObject(it) }
    val answers = controller.answersFor(request.get("id"))
    var sending by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val valid = questions.isNotEmpty() && questions.all { it.optString("id").isNotBlank() } &&
        questions.map { it.optString("id") }.distinct().size == questions.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { question ->
            val id = question.optString("id")
            Text(question.optString("question"), style = MaterialTheme.typography.titleSmall)
            val options = question.optJSONArray("options")
            if (options != null) for (index in 0 until options.length()) {
                val option = options.optJSONObject(index) ?: continue
                val label = option.optString("label")
                QuietChoice(answers[id] == label, { answers[id] = label }, enabled = !sending && controller.ready,
                    label = { Column { Text(label); Text(option.optString("description"), style = MaterialTheme.typography.bodySmall) } })
            }
            if (options == null || options.length() == 0 || question.optBoolean("isOther")) {
                OutlinedTextField(answers[id].orEmpty(), { answers[id] = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("你的答复") }, enabled = !sending && controller.ready,
                    visualTransformation = if (question.optBoolean("isSecret")) PasswordVisualTransformation() else VisualTransformation.None)
            }
        }
        if (!valid) Text("请求格式不完整，请中断本轮后重试。", color = Tokens.current.danger)
        if (error.isNotBlank()) Text(error, color = Tokens.current.danger)
        Button({
            val result = JSONObject()
            questions.forEach { question ->
                val id = question.getString("id")
                result.put(id, JSONObject().put("answers", JSONArray().put(answers[id].orEmpty())))
            }
            sending = true
            scope.launch {
                try {
                    controller.answerRequest(request.get("id"), JSONObject().put("answers", result))
                    answers.clear()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "答复未提交" }
                finally { sending = false }
            }
        }, enabled = valid && !sending && controller.ready && questions.all { !answers[it.optString("id")].isNullOrBlank() }) {
            Text(if (sending) "提交中…" else "提交答复")
        }
    }
}
