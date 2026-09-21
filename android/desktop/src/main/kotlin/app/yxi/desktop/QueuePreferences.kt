package app.yxi.desktop

/** Preference is scoped to the full host/task key, never just the runtime name. */
internal object QueuePreferences {
    fun enabled(taskKey: String) = Store.pref("queue.auto.$taskKey", "true") != "false"
    fun setEnabled(taskKey: String, enabled: Boolean) = Store.setPref("queue.auto.$taskKey", enabled.toString())
}
