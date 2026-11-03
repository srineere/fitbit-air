package com.srineere.airbridge

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
        if (packageName !in selectedPackages ||
            postedAt < startedAt || postedAt > now || now - postedAt >= 30_000) return false
        // Everything below is message hygiene, and a ringing call is exempt
        // from all of it:
        //  - sticky: a ringing call holds its notification up by design;
        //  - summary: WhatsApp posts its call inside a notification group;
        //  - dedupe: a dialer reuses one key for every call and a call has no
        //    message timestamp, so `seen` would let the first call through and
        //    silence every later one.
        // Re-posts while a call rings are absorbed by the ring already running.
        if (kind == RelayAlertKind.CALL) return true
        if (ongoing || summary) return false
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
