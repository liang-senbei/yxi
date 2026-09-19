package app.yxi.desktop

import app.yxi.agent.ChatItem
import app.yxi.agent.Transcript

internal data class TranscriptMemoryView(val offset: Long, val items: List<ChatItem>, val context: Transcript.Ctx?)

/** Bounded in-process cache. A new reader revokes the previous reader's mutation lease. */
internal object DesktopTranscriptMemory {
    class Entry(val file: String, initialOffset: Long) {
        private val parser = Transcript.Incremental()
        private var generation = 0L
        @Volatile var view = TranscriptMemoryView(initialOffset, emptyList(), null)
            private set

        @Synchronized fun claim(): Pair<Long, TranscriptMemoryView> {
            generation++
            return generation to view
        }

        @Synchronized fun release(lease: Long) { if (generation == lease) generation++ }

        @Synchronized fun append(lease: Long, lines: List<String>, bytes: Long): TranscriptMemoryView? {
            if (lease != generation) return null
            require(bytes >= 0)
            parser.add(lines.asSequence())
            // Parser state and resume offset commit together, even if the UI coroutine is cancelled.
            return TranscriptMemoryView(view.offset + bytes, parser.snapshot(), parser.ctx).also { view = it }
        }
    }

    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 6
    }
    @Synchronized fun get(taskKey: String): Entry? = entries[taskKey]
    @Synchronized fun put(taskKey: String, entry: Entry) { entries[taskKey] = entry }
    @Synchronized fun drop(taskKey: String) { entries.remove(taskKey) }
}
