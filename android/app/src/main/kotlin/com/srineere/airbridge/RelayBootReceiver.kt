package com.srineere.airbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the relay back after a reboot or an app update.
 *
 * A sticky service covers a process that was killed while the phone was on,
 * but nothing survives a reboot on its own. Only a relay the user actually
 * started comes back: an explicit Stop clears that, so stopping it stays
 * stopped across reboots.
 *
 * Starting a foreground service from the background is otherwise refused on
 * Android 12 and newer; BOOT_COMPLETED is one of the exemptions, which is why
 * this is a receiver rather than something the listener does when it binds.
 */
class RelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in handled) return
        if (!CalendarRelayService.isWanted(context)) return
        if (!CalendarRelayService.hasAccess(context)) {
            Log.i("AirBridge", "boot: relay wanted but notification access is gone")
            return
        }
        // The listener binds a moment later; the service waits for it.
        runCatching { CalendarRelayService.start(context) }
            .onFailure { Log.w("AirBridge", "boot: could not start relay: ${it.javaClass.simpleName}") }
    }

    private companion object {
        val handled = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
