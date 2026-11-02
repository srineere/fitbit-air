package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.os.Build

/** What a posted notification is, as far as the relay cares. */
internal enum class RelayAlertKind { CALL, MESSAGE }

/**
 * Recognizes a ringing call from metadata Android already exposes; no message
 * text is read.
 *
 * Only a [RelayAlertKind.CALL] may ring, and a ring repeats until the call ends,
 * so a false positive buzzes the wrist for the whole ring timeout. A sticky
 * notification alone is therefore never enough: an app must also mark the alert
 * as a call, ask for the full screen, or post it on a call channel.
 */
internal object RelayAlertClassifier {
    /** Channel IDs messaging apps use for incoming calls. */
    private val callChannel = Regex("call|voip|ring", RegexOption.IGNORE_CASE)

    fun classify(notification: Notification, ongoing: Boolean): RelayAlertKind = classify(
        category = notification.category,
        ongoing = ongoing,
        fullScreen = notification.fullScreenIntent != null,
        channelId = if (Build.VERSION.SDK_INT >= 26) notification.channelId else null,
    )

    internal fun classify(
        category: String?,
        ongoing: Boolean,
        fullScreen: Boolean,
        channelId: String?,
    ): RelayAlertKind {
        // A missed call is an event, not a ringing phone: it must not repeat.
        if (category == Notification.CATEGORY_CALL) return RelayAlertKind.CALL
        if (!ongoing) return RelayAlertKind.MESSAGE
        val ringing = fullScreen || (channelId != null && callChannel.containsMatchIn(channelId))
        return if (ringing) RelayAlertKind.CALL else RelayAlertKind.MESSAGE
    }
}

/**
 * How a call repeats on the Air.
 *
 * One pulse costs a secure session: about 2.5 s over a warm link, and Google
 * Health shares that channel. An interval below [MINIMUM_INTERVAL_SECONDS]
 * would start the next session before the previous pulse is felt, so the
 * wearer gains nothing and Google Health only falls further behind.
 */
internal data class RelayRingSettings(
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
) {
    init {
        require(intervalSeconds in MINIMUM_INTERVAL_SECONDS..60) {
            "Repeat a call every $MINIMUM_INTERVAL_SECONDS–60 seconds."
        }
        require(maxSeconds in 10..300) { "Ring for 10–300 seconds." }
    }

    val intervalMillis: Long get() = intervalSeconds * 1_000L
    val maxMillis: Long get() = maxSeconds * 1_000L

    companion object {
        const val MINIMUM_INTERVAL_SECONDS = 5
        const val DEFAULT_INTERVAL_SECONDS = 6
        const val DEFAULT_MAX_SECONDS = 45
    }
}
