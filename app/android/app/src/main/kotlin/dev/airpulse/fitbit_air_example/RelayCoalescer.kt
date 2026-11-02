package dev.airpulse.fitbit_air_example

/**
 * Merges notification bursts so each Air session is followed by a quiet window.
 *
 * Every command opens a tracker session that competes with Google Health. The
 * first alert dispatches immediately; alerts arriving while a command runs or
 * within [cooldownMillis] after it finished join one follow-up command, sent
 * when the cooldown ends. A burst therefore costs at most two sessions.
 *
 * Google Health resynchronizes at the handoff; manual syncs started 4–17 s
 * later completed in 5–12 s on a Pixel 7 Pro. The 15 s cooldown lets such a
 * sync finish before the next session competes for the tracker channel.
 */
internal class RelayCoalescer(private val cooldownMillis: Long = 15_000) {
    sealed interface Decision {
        /** Dispatch a command for this alert now. */
        data object DispatchNow : Decision
        /** Alert joined the follow-up; schedule it at [atMillis] when non-null. */
        data class Merged(val scheduleAtMillis: Long?) : Decision
    }

    private var running = false
    private var lastFinishedAt: Long? = null
    private val waiting = mutableListOf<String>()

    val hasFollowUp: Boolean get() = waiting.isNotEmpty()

    fun onAlert(alertId: String, nowMillis: Long): Decision {
        val cooled = lastFinishedAt.let { it == null || nowMillis - it >= cooldownMillis }
        if (!running && waiting.isEmpty() && cooled) {
            running = true
            return Decision.DispatchNow
        }
        waiting.add(alertId)
        // A running command schedules the follow-up when it finishes.
        val scheduleAt = if (!running && waiting.size == 1) lastFinishedAt!! + cooldownMillis else null
        return Decision.Merged(scheduleAt)
    }

    /** Returns when to send the follow-up, or null when nothing is waiting. */
    fun onFinished(nowMillis: Long): Long? {
        running = false
        lastFinishedAt = nowMillis
        return if (waiting.isEmpty()) null else nowMillis + cooldownMillis
    }

    /** Starts the follow-up command and returns the alerts it represents. */
    fun takeFollowUp(): List<String> {
        check(!running && waiting.isNotEmpty()) { "No follow-up is ready." }
        running = true
        return waiting.toList().also { waiting.clear() }
    }

    /** Drops a waiting follow-up (relay stopping) and returns its alerts. */
    fun cancelFollowUp(): List<String> = waiting.toList().also { waiting.clear() }
}
