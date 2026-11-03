package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** Demo-only, user-started test. Not an exported remote command endpoint. */
class HeadlessHapticService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var operation = "vibrate"
    private val deadline = Runnable {
        Log.e(TAG, "Headless test exceeded its deadline")
        saveResult("Headless $operation exceeded its service deadline.")
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine != null) return START_NOT_STICKY
        operation = if (intent?.getStringExtra("operation") == "readDeviceInfo") "readDeviceInfo" else "vibrate"
        getSharedPreferences("headless_test", MODE_PRIVATE).edit().remove("result").apply()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Air background test", NotificationManager.IMPORTANCE_LOW)
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Fitbit Air headless test")
            .setContentText("$operation after 15 seconds; automatically stops afterward.")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(901, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(901, notification)
        // Foreground service status alone does not keep the CPU awake.
        // Bound this example's lock, and release it as soon as work finishes.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fitbit_air_example:HeadlessTest")
            .apply { acquire(70_000) }
        handler.postDelayed(deadline, 65_000)
        try {
            val loader = FlutterInjector.instance().flutterLoader()
            loader.startInitialization(applicationContext)
            loader.ensureInitializationComplete(applicationContext, null)
            // Auto-registers plugins, including FitbitAirPlugin, without an Activity.
            val headless = FlutterEngine(applicationContext)
            engine = headless
            MethodChannel(headless.dartExecutor.binaryMessenger, "fitbit_air_example/headless_result")
                .setMethodCallHandler { call, result ->
                    if (call.method == "getOperation") {
                        result.success(operation)
                    } else if (call.method == "complete") {
                        Log.i(TAG, "Headless result: ${call.arguments}")
                        val values = call.arguments as? Map<*, *>
                        saveResult(if (values?.get("success") == true) "Headless $operation succeeded."
                            else "Headless $operation failed: ${values?.get("error") ?: "verification failed"}")
                        result.success(null)
                        handler.post { stopSelf() }
                    } else result.notImplemented()
                }
            Log.i(TAG, "Starting fresh headless engine; no Activity attached; operation=$operation")
            headless.dartExecutor.executeDartEntrypoint(
                DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "airHeadlessTest")
            )
        } catch (error: Exception) {
            Log.e(TAG, "Headless initialization failed", error)
            saveResult("Headless initialization failed: ${error.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(deadline)
        engine?.destroy()
        engine = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveResult(message: String) {
        // Only operation outcome; no raw BLE or health data is persisted.
        getSharedPreferences("headless_test", MODE_PRIVATE).edit().putString("result", message).apply()
    }

    companion object {
        private const val TAG = "AirHeadlessTest"
        private const val CHANNEL = "air_headless_test"
    }
}
