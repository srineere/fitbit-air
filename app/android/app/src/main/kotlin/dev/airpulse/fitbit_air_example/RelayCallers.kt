package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.Person
import android.os.Build
import java.security.MessageDigest

/**
 * Who is calling, taken from the `CallStyle` notification the dialer posts.
 *
 * The identity is a hash of the caller's URI, which is a `tel:` number for a
 * phone call. A number is stable in the ways a name is not: it survives the
 * contact being renamed, and it exists for callers who are not in contacts at
 * all. The raw number never leaves this function; only its hash is stored, and
 * the display name is kept as a label for the user's own list.
 */
internal object RelayCallerIdentity {
    /** Available from API 31 as a constant; the key itself is older. */
    private const val EXTRA_CALL_PERSON = "android.callPerson"

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    data class Caller(val id: String, val label: String)

    @Suppress("DEPRECATION")
    private fun person(notification: Notification): Person? {
        if (Build.VERSION.SDK_INT < 28) return null
        return runCatching {
            notification.extras.getParcelable(EXTRA_CALL_PERSON) as? Person
                ?: (notification.extras.getParcelableArray(Notification.EXTRA_PEOPLE_LIST)
                    ?.firstOrNull() as? Person)
        }.getOrNull()
    }

    /**
     * Null when the call cannot be attributed to anyone, which is normal for
     * apps that do not describe the caller. A rule can then only be made for
     * the app as a whole.
     */
    fun of(packageName: String, notification: Notification): Caller? {
        val person = person(notification)
        // A URI is the number or account; a key is the app's own stable id.
        val stable = person?.uri?.takeIf { it.isNotBlank() }
            ?: person?.key?.takeIf { it.isNotBlank() }
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() }
        // Without a stable handle, a name is better than nothing, but it is
        // the caller's display name and changes when the contact is renamed.
        val identity = stable ?: name ?: return null
        if (identity.length > 512) return null
        return Caller(
            id = hash("caller\u0000$packageName\u0000$identity"),
            label = name ?: "Unknown caller",
        )
    }
}

/**
 * Callers seen recently, so the user can add one without typing a number.
 *
 * Held in memory only and bounded: a caller the user never saves is forgotten
 * when the relay stops. Labels stay on this phone and never reach a report.
 */
internal object RelayRecentCallers {
    private const val LIMIT = 20
    private val recent = linkedMapOf<String, String>()

    @Synchronized
    fun remember(caller: RelayCallerIdentity.Caller) {
        recent.remove(caller.id)
        recent[caller.id] = caller.label
        while (recent.size > LIMIT) recent.remove(recent.keys.first())
    }

    /** Newest first, excluding callers the user has already saved. */
    @Synchronized
    fun list(saved: Set<String>): List<Map<String, String>> = recent.entries
        .reversed()
        .filterNot { it.key in saved }
        .map { mapOf("id" to it.key, "label" to it.value) }

    @Synchronized
    fun clear() = recent.clear()
}
