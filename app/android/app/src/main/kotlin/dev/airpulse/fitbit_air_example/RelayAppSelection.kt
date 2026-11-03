package dev.airpulse.fitbit_air_example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Host-app preferences, deliberately separate from the device-control package. */
internal object RelayAppSelection {
    private const val DEFAULT_APP = "com.google.android.calendar"
    private const val DEFAULT_DIALER = "com.google.android.dialer"

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

    /**
     * Whether this app can receive calls, so only apps that can are offered a
     * call rule.
     *
     * `MANAGE_OWN_CALLS` is what an app needs to put a call through the
     * telecom framework, and messaging apps that call declare it. `CALL_PHONE`
     * is no use as a signal: Calendar and Messages hold it so a tapped number
     * dials out, and neither ever rings.
     */
    private fun canReceiveCalls(context: Context, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains("android.permission.MANAGE_OWN_CALLS") == true
    }.getOrDefault(false)

    /**
     * Apps known to say who is calling, so only they are offered a list of
     * people. An app that rings without naming the caller would give a list
     * that never matches anything, which is worse than not offering one.
     */
    private val namesCallers = setOf(DEFAULT_DIALER, "com.whatsapp", "com.whatsapp.w4b")

    /** An app that has actually named a caller has earned its list. */
    fun rememberNamesCallers(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("naming_apps", emptySet())!!
        if (packageName in seen || seen.size >= 64) return
        prefs.edit().putStringSet("naming_apps", seen + packageName).apply()
    }

    private fun seenNaming(context: Context): Set<String> = context
        .getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        .getStringSet("naming_apps", emptySet())!!

    /**
     * Apps actually seen ringing, in case one calls without declaring it.
     * Observation beats inference: an app that has rung is a calling app
     * whatever its manifest says.
     */
    fun rememberCalling(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("calling_apps", emptySet())!!
        if (packageName in seen || seen.size >= 64) return
        prefs.edit().putStringSet("calling_apps", seen + packageName).apply()
    }

    private fun seenCalling(context: Context): Set<String> = context
        .getSharedPreferences("calendar_relay", Context.MODE_PRIVATE)
        .getStringSet("calling_apps", emptySet())!!

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
        val rang = seenCalling(context)
        val named = seenNaming(context)
        return apps.entries.sortedBy { it.value.lowercase() }.map { app ->
            val entry = mutableMapOf(
                "packageName" to app.key,
                "label" to app.value,
                "calls" to (app.key in rang || canReceiveCalls(context, app.key)).toString(),
                "people" to (app.key in namesCallers || app.key in named).toString(),
            )
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
