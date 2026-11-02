package dev.airpulse.fitbit_air_example

import android.os.Build
import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CalendarNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        connected = true
    }

    override fun onListenerDisconnected() {
        RelayConversations.stopCapture()
        instance = null
        connected = false
        CalendarRelayService.active?.requestStop("Notification access disconnected. Start the relay again when access is available.")
    }

    override fun onDestroy() {
        RelayConversations.stopCapture()
        instance = null
        connected = false
        CalendarRelayService.active?.requestStop("Notification listener stopped. Start the relay again when access is available.")
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { RelayConversations.observe(sbn.packageName, sbn.notification, sbn.isOngoing) }
            .onFailure { RelayConversations.captureError() }
        val relay = CalendarRelayService.active ?: return
        if (!relay.acceptsPackage(sbn.packageName)) return
        // Opt-in: the text stays local to this call; only the tone reaches the relay.
        val tone = if (!relay.toneEnabled) null else runCatching {
            val ranking = NotificationListenerService.Ranking()
                .takeIf { currentRanking?.getRanking(sbn.key, it) == true }
            RelayToneClassifier.classify(latestMessageText(sbn.notification)?.toString(),
                RelayNotificationSignals.from(sbn.notification, ranking, System.currentTimeMillis()),
                RelayToneLexicons.shared(this))
        }.getOrNull()
        relay.onAlert(
            sbn.packageName, sbn.key, sbn.postTime, sbn.isOngoing,
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            latestMessageTimestamp(sbn.notification),
            RelayConversations.identities(sbn.packageName, sbn.notification), tone
        )
    }

    companion object {
        private var instance: CalendarNotificationListener? = null

        @Suppress("DEPRECATION")
        fun latestMessageTimestamp(notification: Notification): Long? {
            // This public framework parser is available from Android 11.
            // Older devices retain conservative once-per-notification behavior.
            if (Build.VERSION.SDK_INT < 30) return null
            return runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).maxOfOrNull { it.timestamp }?.takeIf { it > 0 }
            }.getOrNull()
        }

        @Suppress("DEPRECATION")
        fun latestMessageText(notification: Notification): CharSequence? {
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                    notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                ).maxByOrNull { it.timestamp }?.text
            }.getOrNull()?.let { return it }
            return notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        }

        fun captureExisting() {
            runCatching {
                instance?.activeNotifications?.filter { it.packageName in RelayConversationPolicy.packages }
                    ?.take(128)?.forEach { sbn ->
                        runCatching { RelayConversations.observe(sbn.packageName, sbn.notification, sbn.isOngoing, existing = true) }
                            .onFailure { RelayConversations.captureError() }
                    }
            }.onFailure { RelayConversations.captureError() }
        }

        fun existingKeys(selectedPackages: Set<String>): Map<String, Long?> = instance?.activeNotifications
            ?.filter { it.packageName in selectedPackages }
            ?.associate { it.key to latestMessageTimestamp(it.notification) } ?: emptyMap()

        var connected = false
            private set
    }
}
