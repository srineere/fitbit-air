package dev.airpulse.fitbit_air_example

import android.app.Notification
import android.app.Person
import android.os.Build
import java.security.MessageDigest

/**
 * Who is calling, taken from the `CallStyle` notification the dialer posts.
 *
 * The identity is a hash of the caller's handle, which for a phone call is a
 * `tel:` number. A number is stable in the ways a name is not: it survives the
 * contact being renamed, and it exists for callers who are not in contacts at
 * all. The raw number never leaves this file; only the hash is stored, and the
 * display name is kept as a label for the user's own list.
 */
internal object RelayCallerIdentity {
    /** Separator for hashed parts; not valid inside a number or a package. */
    private const val SEPARATOR = "|"

    /** Available from API 31 as a constant; the key itself is older. */
    private const val EXTRA_CALL_PERSON = "android.callPerson"

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    data class Caller(val id: String, val label: String, val detail: String?)

    /**
     * Compares phone numbers by their last digits, so `+91 98765 43210`,
     * `098765 43210` and `tel:+919876543210` are one person. Two numbers
     * sharing their last ten digits in different countries would collide; on a
     * personal phone that is rarer than one contact written three ways.
     */
    private fun normalize(handle: String): String? {
        val trimmed = handle.removePrefix("tel:").trim()
        val decoded = runCatching {
            java.net.URLDecoder.decode(trimmed, "UTF-8")
        }.getOrDefault(trimmed)
        val digits = decoded.filter { it.isDigit() }
        if (digits.length >= 7) return "tel:" + digits.takeLast(10)
        return decoded.lowercase().takeIf { it.isNotBlank() }
    }

    /**
     * The stored identity for a handle. A phone number deliberately leaves the
     * app out of the hash: the same person calling through the dialer or
     * through a messaging app is still the same person. Anything else is an
     * app's own identifier and only means something inside that app.
     */
    fun identityOf(packageName: String, handle: String): String? {
        if (handle.length > 512) return null
        val normalized = normalize(handle) ?: return null
        val seed = if (normalized.startsWith("tel:")) normalized
        else packageName + SEPARATOR + normalized
        return hash("caller" + SEPARATOR + seed)
    }

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
        // Without a stable handle a name is better than nothing, but it is a
        // display name and changes when the contact is renamed.
        val identity = stable ?: name ?: return null
        return Caller(
            id = identityOf(packageName, identity) ?: return null,
            label = name ?: "Unknown caller",
            // Only a dialable handle is worth showing; an app's internal key
            // would be noise.
            detail = stable?.removePrefix("tel:")?.takeIf { it.any(Char::isDigit) },
        )
    }
}

/**
 * Callers seen recently, so someone who has just called can be given a rule
 * even when they are not in contacts.
 *
 * Held in memory only and bounded: a caller the user never saves is forgotten
 * when the relay stops. Labels stay on this phone and never reach a report.
 */
internal object RelayRecentCallers {
    private const val LIMIT = 20
    private val recent = linkedMapOf<String, RelayCallerIdentity.Caller>()

    @Synchronized
    fun remember(caller: RelayCallerIdentity.Caller) {
        recent.remove(caller.id)
        recent[caller.id] = caller
        while (recent.size > LIMIT) recent.remove(recent.keys.first())
    }

    /** Newest first, excluding callers that already have a rule. */
    @Synchronized
    fun list(saved: Set<String>): List<Map<String, String>> = recent.entries
        .reversed()
        .filterNot { it.key in saved }
        .map {
            mapOf("id" to it.key, "label" to it.value.label, "detail" to (it.value.detail ?: ""))
        }

    @Synchronized
    fun clear() = recent.clear()
}
