package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.service.notification.NotificationListenerService

/** Metadata Android already provides for a notification; no message text is read. */
internal data class RelayNotificationSignals(
    /** Messages in this one-to-one conversation within the burst window. */
    val recentMessages: Int = 0,
    /** The user marked the conversation as priority in Android settings. */
    val priorityConversation: Boolean = false,
    /** A call, missed call, alarm or reminder notification. */
    val urgentCategory: Boolean = false,
    /** The posting app chose a low-importance (silent) channel. */
    val silentChannel: Boolean = false,
) {
    companion object {
        const val BURST_WINDOW_MILLIS = 120_000L
        private val urgentCategories = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM, Notification.CATEGORY_REMINDER, "missed_call",
        )

        /** Timestamps of the other person's messages that fall within the burst window. */
        fun recentCount(timestamps: List<Long>, now: Long) = timestamps.count { now - it in 0..BURST_WINDOW_MILLIS }

        @Suppress("DEPRECATION")
        fun from(notification: Notification, ranking: NotificationListenerService.Ranking?, now: Long): RelayNotificationSignals {
            // Group chats are skipped: several people writing at once is not a burst.
            val group = notification.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
            val recent = if (Build.VERSION.SDK_INT < 30 || group) 0 else runCatching {
                // Messages without a sender are the user's own replies; a quick back-and-forth is not a burst.
                recentCount(Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).filter { it.senderPerson != null || it.sender != null }.map { it.timestamp }, now)
            }.getOrDefault(0)
            val importance = ranking?.importance ?: NotificationManager.IMPORTANCE_UNSPECIFIED
            return RelayNotificationSignals(
                recentMessages = recent,
                priorityConversation = Build.VERSION.SDK_INT >= 30 && ranking?.channel?.isImportantConversation == true,
                urgentCategory = notification.category in urgentCategories,
                silentChannel = importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW,
            )
        }
    }
}
