package dev.airpulse.fitbit_air_example

import android.os.Build
import android.util.Log
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
        // Metadata only: no sender, no message text.
        Log.i("AirBridge", "posted pkg=${sbn.packageName} ongoing=${sbn.isOngoing} " +
            "category=${sbn.notification.category} fullScreen=${sbn.notification.fullScreenIntent != null} " +
            "channel=${if (Build.VERSION.SDK_INT >= 26) sbn.notification.channelId else null}")
        // Opt-in: the text stays local to this call; only the tone reaches the relay.
        val tone = if (!relay.toneEnabled) null else runCatching {
            val ranking = NotificationListenerService.Ranking()
                .takeIf { currentRanking?.getRanking(sbn.key, it) == true }
            RelayToneClassifier.classify(latestMessageText(sbn.notification)?.toString(),
                RelayNotificationSignals.from(sbn.notification, ranking, System.currentTimeMillis()),
                RelayToneLexicons.shared(this))
        }.getOrNull()
        // A notification update is never taken as the call ending: a dialer
        // repaints the same key as an ongoing call while it is still ringing.
        // Only the ringer decides that, in CalendarRelayService's ring watch.
        val kind = RelayAlertClassifier.classify(sbn.notification, sbn.isOngoing)
        relay.onAlert(
            sbn.packageName, sbn.key, sbn.postTime, sbn.isOngoing,
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            latestMessageTimestamp(sbn.notification),
            RelayConversations.identities(sbn.packageName, sbn.notification), tone, kind
        )
    }

    /** A ring stops when the app that was ringing takes its notification back. */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        CalendarRelayService.active?.onAlertRemoved(sbn.key)
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
