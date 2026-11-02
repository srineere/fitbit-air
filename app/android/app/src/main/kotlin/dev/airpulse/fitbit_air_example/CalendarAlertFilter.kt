package dev.airpulse.fitbit_air_example

/** Metadata only. The legacy class name is internal to the original demo. */
internal class CalendarAlertFilter(
    private val startedAt: Long,
    existingKeys: Map<String, Long?> = emptyMap(),
    private val selectedPackages: Set<String> = setOf("com.google.android.calendar")
) {
    private val seen = existingKeys.entries.take(256).associate { it.key to it.value }.toMutableMap()

    fun accept(packageName: String, key: String, postedAt: Long, now: Long,
               ongoing: Boolean, summary: Boolean, messageTimestamp: Long? = null): Boolean {
        if (packageName !in selectedPackages || ongoing || summary ||
            postedAt < startedAt || postedAt > now || now - postedAt >= 30_000) return false
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
