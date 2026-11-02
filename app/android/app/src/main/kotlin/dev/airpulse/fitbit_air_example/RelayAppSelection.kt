package dev.airpulse.fitbit_air_example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Host-app preferences, deliberately separate from the device-control package. */
internal object RelayAppSelection {
    private const val DEFAULT_APP = "com.google.android.calendar"

    fun selected(context: Context): Set<String> = context
        .getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        .getStringSet("selected_apps", setOf(DEFAULT_APP))!!.toSet()

    fun save(context: Context, packages: Set<String>) {
        require(packages.size <= 64 && packages.all {
            it != context.packageName && it.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))
        }) { "Select up to 64 notification apps, excluding this demo." }
        context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            .edit().putStringSet("selected_apps", packages).apply()
    }

    /** "double": two pulses; "single": one tap. Both keep the wearer's strength. */
    fun pulseStyle(context: Context): String = context
        .getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        .getString("pulse_style", "single").takeIf { it in PULSE_STYLES } ?: "single"

    fun savePulseStyle(context: Context, style: String) {
        require(style in PULSE_STYLES) { "Unknown pulse style." }
        context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
            .edit().putString("pulse_style", style).apply()
    }

    private val PULSE_STYLES = setOf("double", "single")

    @Suppress("DEPRECATION")
    fun available(context: Context): List<Map<String, String>> {
        val manager = context.packageManager
        val apps = manager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        ).map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .associate { it.packageName to it.loadLabel(manager).toString() }.toMutableMap()
        // Keep saved selections visible even after an app is uninstalled.
        for (name in selected(context)) apps.putIfAbsent(name,
            if (name == DEFAULT_APP) "Google Calendar" else name)
        return apps.entries.sortedBy { it.value.lowercase() }.map { app ->
            val entry = mutableMapOf("packageName" to app.key, "label" to app.value)
            runCatching {
                val icon = manager.getApplicationIcon(app.key)
                val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val bounds = icon.copyBounds()
                icon.setBounds(0, 0, 48, 48)
                icon.draw(Canvas(bitmap))
                icon.bounds = bounds
                val bytes = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
                bitmap.recycle()
                entry["icon"] = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            }
            entry
        }
    }
}
