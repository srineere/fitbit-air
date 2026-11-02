package dev.airpulse.fitbit_air_example

/** Metadata only. The legacy class name is internal to the original demo. */
internal class CalendarAlertFilter(
    private val startedAt: Long,
    existingKeys: Map<String, Long?> = emptyMap(),
    private val selectedPackages: Set<String> = setOf("com.google.android.calendar")
) {
    private val seen = existingKeys.entries.take(256).associate { it.key to it.value }.toMutableMap()

    fun accept(packageName: String, key: String, postedAt: Long, now: Long,
               ongoing: Boolean, summary: Boolean, messageTimestamp: Long? = null,
               kind: RelayAlertKind = RelayAlertKind.MESSAGE): Boolean {
        // A ringing call is ongoing by design; every other sticky notification
        // is still ignored.
        val stickyBlocks = ongoing && kind != RelayAlertKind.CALL
        if (packageName !in selectedPackages || stickyBlocks || summary ||
            postedAt < startedAt || postedAt > now || now - postedAt >= 30_000) return false
        // Calls skip the message dedupe entirely. A dialer reuses one key for
        // every call and a call carries no message timestamp, so `seen` lets
        // the first call through and silences every later one. Re-posts while
        // a call rings are absorbed by the ring that is already running.
        if (kind == RelayAlertKind.CALL) return true
        if (seen.containsKey(key)) {
            // Messaging apps reuse chat IDs. Only a strictly newer message can buzz again.
            val previous = seen[key]
            if (messageTimestamp == null || messageTimestamp <= (previous ?: (startedAt - 1))) return false
        } else if (seen.size >= 256) return false
        if (messageTimestamp != null && messageTimestamp < startedAt) return false
        seen[key] = messageTimestamp
        return true
    }
}
