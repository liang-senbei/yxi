package app.yxi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

internal class AttachmentMentions(
    val choices: List<Pair<String, DraftAttach>>, val selected: Int,
    val choose: (Int) -> Unit, val handle: (KeyEvent) -> Boolean,
)

@Composable
internal fun rememberAttachmentMentions(items: List<DraftAttach>, draft: TextFieldValue, update: (TextFieldValue) -> Unit): AttachmentMentions {
    val cursor = draft.selection.end
    val before = draft.text.take(cursor)
    val match = if (draft.composition == null && draft.selection.collapsed) Regex("(?<![A-Za-z0-9_@])(@[^\\s@]*)$").find(before) else null
    val start = match?.let { cursor - it.groupValues[1].length }
    val query = match?.groupValues?.get(1)?.drop(1).orEmpty()
    var dismissed by remember { mutableStateOf<String?>(null) }
    val trigger = "$start:$cursor:${draft.text}"
    val choices = if (start != null && dismissed != trigger) attachmentLabels(items).zip(items).filter { (label, item) ->
        label.contains(query, true) || item.name.contains(query, true)
    } else emptyList()
    var selected by remember(start, query, choices.map { it.second.stamp }) { mutableStateOf(0) }
    fun choose(index: Int) {
        val label = choices.getOrNull(index)?.first ?: return
        val from = start ?: return
        val inserted = "@$label "
        val text = draft.text.replaceRange(from, cursor, inserted)
        update(TextFieldValue(text, TextRange(from + inserted.length)))
    }
    return AttachmentMentions(choices, selected, ::choose) { event ->
        if (event.type != KeyEventType.KeyDown || draft.composition != null || choices.isEmpty()) false
        else when (event.key) {
            Key.DirectionDown -> { selected = (selected + 1) % choices.size; true }
            Key.DirectionUp -> { selected = (selected + choices.size - 1) % choices.size; true }
            Key.Enter, Key.NumPadEnter -> { choose(selected); true }
            Key.Escape -> { dismissed = trigger; true }
            else -> false
        }
    }
}

@Composable
internal fun AttachmentMentionList(mentions: AttachmentMentions) {
    if (mentions.choices.isEmpty()) return
    val scroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(mentions.selected) { scroll.animateScrollToItem(mentions.selected) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 14.dp), tonalElevation = 2.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 200.dp), state = scroll) {
            items(mentions.choices.size) { index ->
                val (label, item) = mentions.choices[index]
                Row(Modifier.fillMaxWidth().background(if (index == mentions.selected) Tokens.current.surface3 else Tokens.current.surface2)
                    .clickable { mentions.choose(index) }.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("@$label", style = MaterialTheme.typography.bodyMedium)
                    Text(item.name, style = MaterialTheme.typography.bodySmall, color = Tokens.current.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/** Keep references attached to the same remaining file after ordinal labels change. */
internal fun draftAfterAttachmentRemoval(draft: TextFieldValue, items: List<DraftAttach>, removed: DraftAttach): TextFieldValue {
    val remaining = items.filter { it !== removed }
    val next = attachmentLabels(remaining).zip(remaining).associate { it.second.stamp to it.first }
    val labels = attachmentLabels(items).zip(items).associate { it.first to next[it.second.stamp] }
    val pattern = Regex("@(图片|附件)[0-9]+(?=\\s|$|[，。！？、])")
    fun replace(text: String) = pattern.replace(text) { match ->
        val old = match.value.drop(1)
        if (old in labels) labels[old]?.let { "@$it" }.orEmpty() else match.value
    }
    val text = replace(draft.text)
    val cursor = replace(draft.text.take(draft.selection.end)).length.coerceAtMost(text.length)
    return TextFieldValue(text, TextRange(cursor))
}
