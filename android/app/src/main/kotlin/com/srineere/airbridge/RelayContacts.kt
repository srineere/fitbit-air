package com.srineere.airbridge

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
    /**
     * The phone number behind a contacts lookup URI, which is how a dialer
     * names the caller. Null without the contacts permission, or when the
     * handle is not a lookup URI, in which case the caller is identified by
     * the handle itself and simply will not line up with a contact.
     */
    /** One entry per contact, identified the way a named caller arrives. */
    private fun byName(context: Context, packageName: String): List<Map<String, String>> {
        val found = linkedMapOf<String, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                while (cursor.moveToNext() && found.size < LIMIT) {
                    val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val id = RelayCallerIdentity.identityOf(packageName, name) ?: continue
                    found.putIfAbsent(id, name)
                }
            }
        }
        return found.entries.map { mapOf("id" to it.key, "label" to it.value, "detail" to "") }
    }

    fun numberFor(context: Context, handle: String): String? {
        if (!handle.startsWith("content://") || !granted(context)) return null
        return runCatching {
            val contactId = ContactsContract.Contacts.lookupContact(
                context.contentResolver, android.net.Uri.parse(handle),
            )?.lastPathSegment ?: return null
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId), null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /**
     * Apps that name a caller rather than number them. WhatsApp attaches only
     * a display name, which is the contact's own name, so a contact can be
     * matched by name. That is weaker than a number: renaming the contact
     * breaks the rule, and two people with one name cannot be told apart.
     */
    private val namedCallers = setOf("com.whatsapp", "com.whatsapp.w4b")

    fun list(context: Context, packageName: String = "com.google.android.dialer"):
        List<Map<String, String>> {
        if (!granted(context)) return emptyList()
        if (packageName in namedCallers) return byName(context, packageName)
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
