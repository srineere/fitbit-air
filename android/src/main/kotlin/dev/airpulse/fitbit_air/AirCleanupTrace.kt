package dev.airpulse.fitbit_air

/** Fixed labels only: never retain exception messages, device IDs or payloads. */
internal class AirCleanupTrace {
    private val steps = linkedMapOf<String, String>()

    fun step(name: String, present: Boolean = true, action: () -> Unit) {
        steps[name] = if (!present) "not_present" else {
            try {
                action()
                "returned"
            } catch (_: Exception) {
                "failed"
            }
        }
    }

    /** Records a fixed outcome label rather than a call result. */
    fun note(name: String, outcome: String) {
        steps[name] = outcome
    }

    fun snapshot(): Map<String, String> = steps.toMap()
}

/** Shared by UI and worker engines; intentionally limited to this process lifetime. */
internal object AirTransportDiagnostics {
    private val history = ArrayDeque<Map<String, Any>>()
    private val processStartedAtMillis = System.currentTimeMillis()

    @Synchronized
    fun record(operation: String, steps: Map<String, String>) {
        if (history.size == 16) history.removeFirst()
        history.addLast(mapOf(
            "operation" to operation,
            "completedAtMillis" to System.currentTimeMillis(),
            "steps" to steps.toMap(),
        ))
    }

    @Synchronized
    fun snapshot(): Map<String, Any> = mapOf(
        "scope" to "command_cleanup_current_process",
        "processStartedAtMillis" to processStartedAtMillis,
        "appLeaseHeld" to AirTransportLease.isHeld(),
        "cleanupHistory" to history.toList(),
    )
}
