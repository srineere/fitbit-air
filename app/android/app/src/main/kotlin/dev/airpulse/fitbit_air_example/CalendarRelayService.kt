package dev.airpulse.fitbit_air_example

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** User-started example relay; never starts work from a notification callback. */
class CalendarRelayService : Service() {
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var filter: CalendarAlertFilter? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pending = 0
    private var ready = false
    private var stopping = false
    private var sequence = 0
    private val inFlight = mutableMapOf<String, Pair<String?, Long>>()
    private var selectedPackages = emptySet<String>()
    /** Read by the listener so message text is only inspected after the user opts in. */
    var toneEnabled = false
        private set
    private var pulseStyle = "single"
    private val previewReplies = mutableMapOf<String, (RelayWorkerResult) -> Unit>()
    private val choices = mutableMapOf<String, RelayBurstChoice>()
    private var displayedSilence: String? = null
    private val silenceTick = Runnable { settingsChanged() }
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { settingsChanged() }
    }

    /** One call ringing on the Air. [key] is the notification that started it. */
    private class RingSession(
        val key: String,
        val packageName: String,
        val match: RelayMatch,
        val startedAt: Long,
        /**
         * The audio mode when the ring began. Some apps already hold the audio
         * session while they are still ringing, so only a *change* into an
         * in-call mode means the call was picked up.
         */
        val startMode: Int,
    ) {
        /** The phone was heard ringing, so a return to silence means it ended. */
        var sawRingtone: Boolean = startMode == RelayRingWatch.MODE_RINGTONE
    }

    private var ring: RingSession? = null
    private var ringSettings = RelayRingSettings()

    /**
     * The last call that rang. Calls no longer go through the message dedupe,
     * so without this a phone still ringing after the duration ran out would
     * start a fresh ring on its next notification update, forever.
     */
    private var lastRingKey: String? = null
    private var lastRingStoppedAt = 0L
    private var lastRingTimedOut = false

    /**
     * Unlocking is NOT a reason to stop ringing. On a phone with face unlock,
     * an incoming call wakes the screen and unlocks it within a second, which
     * killed every ring that arrived while the phone was locked. Unlocking to
     * see who is calling is not the same as dealing with the call; answering
     * is caught by the audio mode and by the notification going away.
     */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ring != null) Log.i(TAG, "unlocked while ringing; ring continues")
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(clockReceiver, filter)
        val present = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(userPresentReceiver, present, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(userPresentReceiver, present)
    }

    fun settingsChanged() {
        val config = RelayRuleStore.read(this)
        val packages = config.selectedPackages
        toneEnabled = config.tone.enabled
        ringSettings = config.ring
        if (toneEnabled) RelayToneLexicons.preload(this)
        if (packages != selectedPackages) {
            selectedPackages = packages
            if (ready) filter = CalendarAlertFilter(System.currentTimeMillis(),
                CalendarNotificationListener.existingKeys(packages), packages)
        }
        val now = System.currentTimeMillis()
        val reason = config.silence.reason(now)
        if (reason != null) cancelWaiting(reason)
        if (ready && reason != displayedSilence) {
            displayedSilence = reason
            getSystemService(NotificationManager::class.java).notify(902, buildNotification())
        }
        handler.removeCallbacks(silenceTick)
        if (!stopping) {
            val until = config.silence.pauseUntil
            val delay = if (until > now) minOf(60_000L - now % 60_000L, until - now) else 60_000L - now % 60_000L
            handler.postDelayed(silenceTick, maxOf(1L, delay))
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(this, 0, Intent(this, CalendarRelayService::class.java).setAction(STOP), flags)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        val silence = RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())
        val pause = PendingIntent.getService(this, 2, Intent(this, CalendarRelayService::class.java)
            .setAction(if (silence == "paused") RESUME else PAUSE), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Air Relay")
            .setContentText(when {
                ring != null -> "Ringing your Air · tap Silence to stop"
                silence == "paused" -> "Paused · No notification vibrations"
                silence == "quiet_hours" -> "Quiet hours · No notification vibrations"
                else -> "Listening for your notification rules"
            })
            .setContentIntent(open).setOngoing(true)
        // Silencing a ring is the only useful action while the wrist is buzzing.
        if (ring != null) {
            val silenceRing = PendingIntent.getService(this, 3, Intent(this, CalendarRelayService::class.java).setAction(SILENCE), flags)
            return builder.addAction(Notification.Action.Builder(null, "Silence", silenceRing).build())
                .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        }
        return builder
            .addAction(Notification.Action.Builder(null, if (silence == "paused") "Resume" else "Pause 15 min", pause).build())
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun refreshNotification() {
        if (!ready) return
        runCatching { getSystemService(NotificationManager::class.java).notify(902, buildNotification()) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val coalescer = RelayCoalescer()
    private var holdingFollowUp = false
    private val followUp = Runnable { sendFollowUp() }
    private val watchdog = Runnable {
        saveOutcome("Relay command exceeded its deadline; stopped. Delivery is uncertain; no retry.")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        starting = false
        if (cancelStart) {
            cancelStart = false
            saveOutcome("Relay stopped.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) {
            requestStop("Relay stopped.")
            return START_NOT_STICKY
        }
        if (intent?.action == SILENCE) {
            stopRing("silenced")
            if (engine == null) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == PAUSE || intent?.action == RESUME) {
            RelayRuleStore.update(this) { it.copy(silence = it.silence.copy(pauseUntil = if (intent.action == RESUME) 0 else System.currentTimeMillis() + 900_000)) }
            if (engine == null) stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY
        selectedPackages = RelayRuleStore.read(this).selectedPackages
        toneEnabled = RelayRuleStore.read(this).tone.enabled
        if (toneEnabled) RelayToneLexicons.preload(this)
        pulseStyle = RelayAppSelection.pulseStyle(this)
        if (selectedPackages.isEmpty()) {
            saveOutcome("Select at least one notification app before starting.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasAccess(this) || !CalendarNotificationListener.connected) {
            saveOutcome("Enable notification access and wait for the listener to connect, then start again.")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Notifications to Air", NotificationManager.IMPORTANCE_LOW)
            )
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(902, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(902, notification)
            active = this
            saveOutcome("Starting notification relay…")
            val loader = FlutterInjector.instance().flutterLoader()
            loader.startInitialization(applicationContext)
            loader.ensureInitializationComplete(applicationContext, null)
            val relayEngine = FlutterEngine(applicationContext)
            engine = relayEngine
            channel = MethodChannel(relayEngine.dartExecutor.binaryMessenger, "fitbit_air_example/calendar_worker")
                .also { worker ->
                    worker.setMethodCallHandler { call, result ->
                        if (call.method == "ready") {
                            try {
                                check(hasAccess(this) && CalendarNotificationListener.connected)
                                filter = CalendarAlertFilter(System.currentTimeMillis(),
                                    CalendarNotificationListener.existingKeys(selectedPackages), selectedPackages)
                                ready = true
                                settingsChanged()
                                handler.removeCallbacks(watchdog)
                                saveOutcome("Listening for alerts from ${selectedPackages.size} selected app(s).")
                                result.success(null)
                                if (stopping) requestStop("Relay stopped.")
                            } catch (error: Exception) {
                                result.error("NOTIFICATION_ACCESS", "Notification listener unavailable.", null)
                                requestStop("Notification listener unavailable. Enable access and start again.")
                            }
                        } else result.notImplemented()
                    }
                }
            handler.postDelayed(watchdog, 60_000)
            relayEngine.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "airCalendarRelay"))
        } catch (error: Exception) {
            saveOutcome("Relay startup failed: ${error.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    fun acceptsPackage(packageName: String): Boolean = ready && !stopping && packageName in selectedPackages

    internal fun onAlert(packageName: String, key: String, postedAt: Long, ongoing: Boolean, summary: Boolean, messageTimestamp: Long?, conversationIds: Set<String>, tone: RelayTone? = null, kind: RelayAlertKind = RelayAlertKind.MESSAGE) {
        if (!ready || stopping) return
        val now = System.currentTimeMillis()
        if (filter?.accept(packageName, key, postedAt, now, ongoing, summary, messageTimestamp, kind) != true) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=filter " +
                "age=${now - postedAt}ms ongoing=$ongoing summary=$summary")
            return
        }
        val config = RelayRuleStore.read(this)
        val match = config.match(packageName, conversationIds, tone, kind)
        val category = tone?.takeIf { config.tone.enabled }
        if (match == null) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=rule (silent or app off)")
            record(nextId(), packageName, postedAt, "filtered", tone = category)
            return
        }
        val silence = config.silence.reason(now)
        if (silence != null) {
            Log.i(TAG, "dropped pkg=$packageName kind=$kind by=$silence")
            cancelWaiting(silence)
            record(nextId(), packageName, postedAt, silence, tone = category)
            return
        }
        // One ring at a time, and nothing else buzzes during it: a message pulse
        // in the middle of a ring is indistinguishable from the ring itself.
        Log.i(TAG, "alert pkg=$packageName kind=$kind ongoing=$ongoing preset=${match.preset.id}")
        if (ring != null) { record(nextId(), packageName, postedAt, "ringing", tone = category); return }
        if (match.preset == RelayPreset.RING) {
            if (rangAlready(key, now)) { record(nextId(), packageName, postedAt, "ring_done"); return }
            startRing(key, packageName, match)
            return
        }
        submit(packageName, postedAt, match)
    }

    /**
     * This call already had its ring: either it has only just stopped, or it
     * ran its full duration and the phone is still ringing. Either way the
     * wrist has said its piece until the call ends.
     */
    private fun rangAlready(key: String, now: Long): Boolean =
        key == lastRingKey && (lastRingTimedOut || now - lastRingStoppedAt < RING_REARM_MILLIS)

    /**
     * Polls the audio mode, because once the ringing notification has been
     * replaced there is nothing left to tell us how the call ended.
     */
    private val ringWatch = object : Runnable {
        override fun run() {
            val session = ring ?: return
            val mode = audioMode()
            if (mode == RelayRingWatch.MODE_RINGTONE) session.sawRingtone = true
            val reason = RelayRingWatch.reason(session.startMode, session.sawRingtone, mode)
            if (reason != null) stopRing(reason) else handler.postDelayed(this, RING_WATCH_MILLIS)
        }
    }

    /** A ring stops when the call is answered, declined, cleared, or times out. */
    internal fun onAlertRemoved(key: String) {
        if (ring?.key == key) {
            // A locked phone swaps the ringing notification for its full-screen
            // call UI. That is not the call ending, and the ringer says so.
            if (RelayRingWatch.stopOnRemoval(audioMode())) stopRing("cleared")
            else Log.i(TAG, "ringing notification replaced; phone still ringing")
        }
        // The call is over, so this key may ring again when it next comes in.
        if (key == lastRingKey) { lastRingKey = null; lastRingTimedOut = false }
    }

    private fun startRing(key: String, packageName: String, match: RelayMatch) {
        val session = RingSession(key, packageName, match, System.currentTimeMillis(), audioMode())
        ring = session
        Log.i(TAG, "ring start pkg=$packageName gap=${ringSettings.gapMillis}ms max=${ringSettings.maxSeconds}s mode=${session.startMode}")
        record(nextId(), packageName, session.startedAt, "ring_started", match = match)
        refreshNotification()
        handler.postDelayed(ringWatch, RING_WATCH_MILLIS)
        sendRing()
    }

    private fun stopRing(reason: String) {
        val session = ring ?: return
        ring = null
        handler.removeCallbacks(ringWatch)
        lastRingKey = session.key
        lastRingStoppedAt = System.currentTimeMillis()
        lastRingTimedOut = reason == "ring_timeout"
        Log.i(TAG, "ring stop reason=$reason after=${System.currentTimeMillis() - session.startedAt}ms")
        record(nextId(), session.packageName, session.startedAt, "ring_stopped", reason, session.match)
        // Releases the held session; the in-flight ring call then completes on
        // its own and does its own bookkeeping.
        runCatching { channel?.invokeMethod("stopRing", null) }
        // Give Google Health the usual quiet window now that the ring is over.
        coalescer.onFinished(System.currentTimeMillis())
        refreshNotification()
    }

    private fun audioMode(): Int = runCatching {
        getSystemService(AudioManager::class.java).mode
    }.getOrDefault(AudioManager.MODE_NORMAL)

    /** True once this call was picked up, including WhatsApp and Instagram calls. */
    private fun answered(session: RingSession): Boolean =
        RelayRingWatch.reason(session.startMode, session.sawRingtone, audioMode()) != null

    /**
     * Starts one held ring. The worker keeps the secure session open and
     * pulses inside it, so this is a single long call rather than a pulse
     * schedule: stopping means telling the worker, not cancelling a timer.
     */
    private fun sendRing() {
        val session = ring ?: return
        if (stopping || !ready) { stopRing("cancelled"); return }
        if (answered(session)) { stopRing("answered"); return }
        RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())?.let { stopRing(it); return }
        val worker = channel
        if (worker == null) { stopRing("worker_unavailable"); return }
        val id = nextId()
        begin()
        refreshDeadline()
        inFlight[id] = session.packageName to session.startedAt
        record(id, session.packageName, session.startedAt, "queued", match = session.match)
        fun finished(result: RelayWorkerResult) {
            inFlight.remove(id)
            record(id, session.packageName, session.startedAt, result.stage, result.errorCode, session.match)
            Log.i(TAG, "ring result stage=${result.stage} code=${result.errorCode}")
            saveOutcome("Ring ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            end()
            // Reaching the duration ends the ring without anyone asking.
            if (ring === session) stopRing("ring_finished")
        }
        worker.invokeMethod("ring", mapOf(
            "gapMillis" to ringSettings.gapMillis,
            "maxMillis" to ringSettings.maxMillis.toInt(),
            "intensity" to session.match.preset.intensity,
        ), object : MethodChannel.Result {
            override fun success(result: Any?) { finished(RelayWorkerResult.decode(result)) }
            override fun error(code: String, message: String?, details: Any?) { finished(RelayWorkerResult("failed", "WORKER_ERROR")) }
            override fun notImplemented() { finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE")) }
        })
    }

    internal fun testPulse(preset: String? = null, completion: ((RelayWorkerResult) -> Unit)? = null) {
        check(ready && !stopping) { "Wait until the relay is listening." }
        val config = RelayRuleStore.read(this)
        check(config.silence.reason(System.currentTimeMillis()) == null) { "Resume the relay or leave quiet hours before trying a vibration." }
        val selected = preset?.let { RelayPreset.parse(it) } ?: config.defaultPreset
        require(selected != RelayPreset.MUTE && !selected.callsOnly) { "Choose a vibration to try." }
        submit(null, System.currentTimeMillis(), RelayMatch(selected, 2), completion)
    }

    private fun submit(sbnPackage: String?, postedAt: Long, match: RelayMatch, completion: ((RelayWorkerResult) -> Unit)? = null) {
        val id = nextId()
        if (choices.size >= 256) {
            record(id, sbnPackage, postedAt, "queue_full")
            completion?.invoke(RelayWorkerResult("failed", "QUEUE_FULL"))
            return
        }
        if (completion != null) previewReplies[id] = completion
        choices[id] = RelayBurstChoice(match, System.currentTimeMillis())
        record(id, sbnPackage, postedAt, "detected", match = match)
        val now = System.currentTimeMillis()
        when (val decision = coalescer.onAlert(id, now)) {
            RelayCoalescer.Decision.DispatchNow -> dispatch(id, sbnPackage, postedAt, postedAt + 30_000, match)
            is RelayCoalescer.Decision.Merged -> {
                if (!holdingFollowUp) { holdingFollowUp = true; begin() }
                inFlight[id] = sbnPackage to postedAt
                record(id, sbnPackage, postedAt, "merged", match = match)
                decision.scheduleAtMillis?.let { handler.postDelayed(followUp, maxOf(0, it - now)) }
            }
        }
    }

    private fun cancelWaiting(reason: String) {
        handler.removeCallbacks(followUp)
        coalescer.cancelFollowUp().forEach { id ->
            choices.remove(id)
            previewReplies.remove(id)?.invoke(RelayWorkerResult("failed", "SUPPRESSED"))
            inFlight.remove(id)?.let { (source, postedAt) -> record(id, source, postedAt, reason) }
        }
        if (holdingFollowUp) { holdingFollowUp = false; end() }
    }

    private fun sendFollowUp() {
        if (stopping || !coalescer.hasFollowUp) return
        val now = System.currentTimeMillis()
        val silence = RelayRuleStore.read(this).silence.reason(now)
        if (silence != null) { cancelWaiting(silence); return }
        val members = coalescer.takeFollowUp().mapNotNull { id ->
            val source = inFlight.remove(id)
            val choice = choices.remove(id)
            if (source == null || choice == null) null else Triple(id, source, choice)
        }
        val fresh = members.filter { now >= it.second.second && now - it.second.second < 30_000 }
        members.filterNot { it in fresh }.forEach {
            record(it.first, it.second.first, it.second.second, "expired")
            previewReplies.remove(it.first)?.invoke(RelayWorkerResult("failed", "EXPIRED"))
        }
        val winner = fresh.reduceOrNull { a, b -> if (a.third.prefer(b.third) === b.third) b else a }
        fresh.forEach { record(it.first, it.second.first, it.second.second, "combined", match = it.third.match) }
        if (winner != null) {
            val id = nextId()
            val replies = fresh.mapNotNull { previewReplies.remove(it.first) }
            if (replies.isNotEmpty()) previewReplies[id] = { result -> replies.forEach { it(result) } }
            dispatch(id, winner.second.first, winner.second.second, winner.second.second + 30_000, winner.third.match)
        } else coalescer.onFinished(now)
        holdingFollowUp = false
        end()
    }

    internal fun readBattery(reply: MethodChannel.Result) = readDeviceStatus("readBattery", reply)
    internal fun readSignal(reply: MethodChannel.Result) = readDeviceStatus("readSignalStrength", reply)
    internal fun readDeviceInfo(reply: MethodChannel.Result) = readDeviceStatus("readDeviceInfo", reply)

    private fun readDeviceStatus(operation: String, reply: MethodChannel.Result) {
        val worker = channel
        if (!ready || stopping || pending != 0 || worker == null) {
            reply.error("BUSY", "The relay is delivering a notification. Try again shortly.", null)
            return
        }
        begin()
        worker.invokeMethod(operation, null, object : MethodChannel.Result {
            override fun success(result: Any?) { end(); reply.success(result) }
            override fun error(code: String, message: String?, details: Any?) { end(); reply.error(code, message, details) }
            override fun notImplemented() { end(); reply.error("WORKER_UNAVAILABLE", "Device read unavailable.", null) }
        })
    }

    private fun dispatch(id: String, sbnPackage: String?, postedAt: Long, expiresAt: Long, match: RelayMatch) {
        val preset = match.preset
        begin()
        refreshDeadline()
        inFlight[id] = sbnPackage to postedAt
        record(id, sbnPackage, postedAt, "queued", match = match)
        fun finished(result: RelayWorkerResult) {
            previewReplies.remove(id)?.invoke(result)
            choices.remove(id)
            inFlight.remove(id)
            record(id, sbnPackage, postedAt, result.stage, result.errorCode, match)
            saveOutcome("Haptic ${result.stage}${result.errorCode?.let { ": $it" } ?: ""}.")
            val now = System.currentTimeMillis()
            coalescer.onFinished(now)?.let { if (!stopping) handler.postDelayed(followUp, maxOf(0, it - now)) }
            end()
        }
        val silence = RelayRuleStore.read(this).silence.reason(System.currentTimeMillis())
        if (silence != null || System.currentTimeMillis() >= expiresAt) {
            finished(RelayWorkerResult(silence ?: "expired", null)); return
        }
        channel?.invokeMethod("dispatch", mapOf(
            "id" to id, "expiresAt" to expiresAt,
            "kind" to if (preset == RelayPreset.DOUBLE) "preview" else "tap", "intensity" to preset.intensity
        ), object : MethodChannel.Result {
            override fun success(result: Any?) { finished(RelayWorkerResult.decode(result)) }
            override fun error(code: String, message: String?, details: Any?) { finished(RelayWorkerResult("failed", "WORKER_ERROR")) }
            override fun notImplemented() { finished(RelayWorkerResult("failed", "WORKER_UNAVAILABLE")) }
        })
    }

    /** Running commands and a waiting follow-up each hold the wake lock and watchdog. */
    private fun begin() {
        if (pending++ == 0) refreshDeadline()
    }

    /** The watchdog bounds one hung command, so a long burst re-arms it per dispatch. */
    private fun refreshDeadline() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 240_000)
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitbit_air_example:CalendarRelay")
            .apply { setReferenceCounted(false) }
        wakeLock = lock
        lock.acquire(240_000)
    }

    private fun end() {
        if (--pending == 0) {
            handler.removeCallbacks(watchdog)
            releaseWakeLock()
            if (stopping) stopSelf()
        }
    }

    private fun nextId() = "relay-${System.currentTimeMillis()}-${++sequence}"

    private fun record(id: String, sbnPackage: String?, postedAt: Long, stage: String, code: String? = null,
                       match: RelayMatch? = null, tone: RelayTone? = match?.tone) {
        val now = System.currentTimeMillis()
        RelayHistory.add(this, RelayActivity(id, sbnPackage, stage, now, maxOf(0, now - postedAt), code,
            match?.preset?.id, tone?.id))
    }

    fun requestStop(reason: String) {
        stopping = true
        stopRing("cancelled")
        saveOutcome(reason)
        channel?.invokeMethod("close", null)
        handler.removeCallbacks(silenceTick)
        cancelWaiting("cancelled")
        if (pending == 0) stopSelf()
    }

    override fun onDestroy() {
        ready = false
        stopping = true
        val endedAt = System.currentTimeMillis()
        inFlight.forEach { (id, source) ->
            RelayHistory.add(this, RelayActivity(id, source.first, "interrupted", endedAt,
                maxOf(0, endedAt - source.second), "SERVICE_STOPPED"))
        }
        inFlight.clear()
        choices.clear()
        val replies = previewReplies.values.toList()
        previewReplies.clear()
        replies.forEach { it(RelayWorkerResult("failed", "SERVICE_STOPPED")) }
        ring = null
        handler.removeCallbacks(ringWatch)
        unregisterReceiver(clockReceiver)
        unregisterReceiver(userPresentReceiver)
        handler.removeCallbacks(silenceTick)
        if (active === this) active = null
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(followUp)
        channel?.setMethodCallHandler(null)
        channel = null
        engine?.destroy()
        engine = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun saveOutcome(value: String) {
        getSharedPreferences("calendar_relay", MODE_PRIVATE).edit().putString("outcome", value).apply()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private var starting = false
        private var cancelStart = false
        val isRunning: Boolean get() = active != null || starting

        fun start(context: Context) {
            if (active != null || starting) return
            starting = true
            cancelStart = false
            try {
                val intent = Intent(context, CalendarRelayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (error: Exception) {
                starting = false
                throw error
            }
        }

        fun stop() {
            if (starting) cancelStart = true
            active?.requestStop("Relay stopped.")
        }

        private const val TAG = "AirBridge"
        private const val RING_REARM_MILLIS = 15_000L
        private const val RING_WATCH_MILLIS = 1_000L
        private const val CHANNEL = "calendar_relay"
        private const val STOP = "calendar_relay.STOP"
        private const val SILENCE = "calendar_relay.SILENCE"
        private const val PAUSE = "calendar_relay.PAUSE"
        private const val RESUME = "calendar_relay.RESUME"
        var active: CalendarRelayService? = null
            private set

        fun hasAccess(context: Context): Boolean {
            val expected = ComponentName(context, CalendarNotificationListener::class.java)
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        }

        private fun bluetoothPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun status(context: Context): Map<String, Any?> = mapOf(
            "notificationControlsAllowed" to context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled(),
            "setupStep" to context.getSharedPreferences("relay_onboarding", MODE_PRIVATE).getInt("step", 0).coerceIn(0, 3),
            "setupCompleted" to context.getSharedPreferences("relay_onboarding", MODE_PRIVATE).getBoolean("completed", false),
            "access" to hasAccess(context),
            "connected" to CalendarNotificationListener.connected,
            "running" to isRunning,
            "ready" to (active?.ready == true),
            "stopping" to (active?.stopping == true),
            "ringing" to (active?.ring != null),
            "selectedApps" to RelayRuleStore.read(context).selectedPackages.sorted(),
            "pulseStyle" to RelayRuleStore.read(context).defaultPreset.id,
            "rules" to RelayRuleStore.status(context),
            "conversations" to RelayConversations.status(),
            "history" to RelayHistory.read(context).asReversed().map { it.toMap() },
            "bluetoothPermission" to bluetoothPermission(context),
            "bluetoothEnabled" to if (bluetoothPermission(context)) runCatching {
                context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled
            }.getOrNull() else null,
            "outcome" to context.getSharedPreferences("calendar_relay", MODE_PRIVATE).getString("outcome", null)
        )
    }
}
