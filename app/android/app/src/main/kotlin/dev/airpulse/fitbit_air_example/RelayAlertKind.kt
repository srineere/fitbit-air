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
    /** Channel IDs apps use while a call is still ringing. */
    private val ringingChannel = Regex("incoming|voip|ring", RegexOption.IGNORE_CASE)

    /**
     * Channel IDs for calls that are no longer ringing. Matching "call" alone
     * is not enough: a dialer's answered call sits on `phone_ongoing_call` and
     * a missed one on `phone_missed_call`, and buzzing the wrist through a
     * conversation you are already having is worse than missing the call.
     */
    private val settledChannel =
        Regex("ongoing|active|missed|voicemail|history", RegexOption.IGNORE_CASE)

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
        // A phone that is still ringing holds its notification up; a missed
        // call is an event, and an answered one is already in your hand.
        if (channelId != null && settledChannel.containsMatchIn(channelId)) return RelayAlertKind.MESSAGE
        if (!ongoing) return RelayAlertKind.MESSAGE
        val ringing = category == Notification.CATEGORY_CALL || fullScreen ||
            (channelId != null && ringingChannel.containsMatchIn(channelId))
        return if (ringing) RelayAlertKind.CALL else RelayAlertKind.MESSAGE
    }
}

/**
 * How a call repeats on the Air.
 *
 * The ring holds one secure session and posts a pulse every [gapMillis], so
 * the gap is a real pulse rhythm rather than the cost of a fresh connection.
 * The Air plays one pulse per change it notices, so a gap shorter than a pulse
 * merges buzzes into fewer, longer ones.
 */
internal data class RelayRingSettings(
    val gapMillis: Int = DEFAULT_GAP_MILLIS,
    val maxSeconds: Int = DEFAULT_MAX_SECONDS,
) {
    init {
        require(gapMillis in MINIMUM_GAP_MILLIS..5_000) {
            "Pulses must be $MINIMUM_GAP_MILLIS-5000 ms apart."
        }
        // Bounded below the relay's own 240 s command watchdog.
        require(maxSeconds in 10..180) { "Ring for 10-180 seconds." }
    }

    val maxMillis: Long get() = maxSeconds * 1_000L

    companion object {
        const val MINIMUM_GAP_MILLIS = 250
        const val DEFAULT_GAP_MILLIS = 700
        const val DEFAULT_MAX_SECONDS = 45
    }
}
