package dev.airpulse.fitbit_air_example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract

/**
 * People from the phone's contacts, so a rule can be made before they call.
 *
 * Only the display name leaves this file. Numbers are turned into the same
 * identity hash a call arrives with and are never stored or reported, so a
 * contact and an incoming call agree on who they are without the number
 * living anywhere.
 */
internal object RelayContacts {
    private const val LIMIT = 2_000

    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Contacts with a phone number, newest-sorted by name, one entry per
     * distinct number. A contact with two numbers appears twice, because a
     * call only ever arrives on one of them.
     */
    fun list(context: Context, packageName: String = "com.google.android.dialer"):
        List<Map<String, String>> {
        if (!granted(context)) return emptyList()
        val found = linkedMapOf<String, Pair<String, String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && found.size < LIMIT) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val number = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val id = RelayCallerIdentity.identityOf(packageName, number) ?: continue
                    found.putIfAbsent(id, name to number)
                }
            }
        }
        return found.entries.map {
            mapOf("id" to it.key, "label" to it.value.first, "detail" to it.value.second)
        }
    }
}
