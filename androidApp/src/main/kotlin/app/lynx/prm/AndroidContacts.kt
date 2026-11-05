package app.lynx.prm

import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import app.lynx.prm.contacts.LabeledValue
import app.lynx.prm.contacts.ProviderContact
import app.lynx.prm.contacts.toImport
import app.lynx.prm.store.ContactImport

fun readDeviceContacts(context: Context): List<ContactImport> {
    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val people = linkedMapOf<String, Accumulator>()
    val projection = arrayOf(
        ContactsContract.Data.LOOKUP_KEY,
        ContactsContract.Data.DISPLAY_NAME_PRIMARY,
        ContactsContract.Data.MIMETYPE,
        ContactsContract.CommonDataKinds.Organization.COMPANY,
        ContactsContract.CommonDataKinds.Organization.TITLE,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.TYPE,
        ContactsContract.CommonDataKinds.Phone.LABEL,
        ContactsContract.CommonDataKinds.Email.ADDRESS,
        ContactsContract.CommonDataKinds.Email.TYPE,
        ContactsContract.CommonDataKinds.Email.LABEL,
    )
    val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?)"
    val args = arrayOf(
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
    )
    context.contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
        val keyIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.LOOKUP_KEY)
        val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.DISPLAY_NAME_PRIMARY)
        val mimeIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.MIMETYPE)
        val companyIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.COMPANY)
        val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Organization.TITLE)
        val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val phoneTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE)
        val phoneLabelIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LABEL)
        val emailIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
        val emailTypeIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.TYPE)
        val emailLabelIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.LABEL)
        while (cursor.moveToNext()) {
            val key = cursor.getString(keyIndex) ?: continue
            val person = people.getOrPut(key) { Accumulator() }
            cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { person.displayName = it }
            when (cursor.getString(mimeIndex)) {
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                    val number = cursor.getString(numberIndex) ?: continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        cursor.getInt(phoneTypeIndex),
                        cursor.getString(phoneLabelIndex),
                    ).toString()
                    person.phones += LabeledValue(number, label)
                }
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                    val address = cursor.getString(emailIndex) ?: continue
                    val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                        context.resources,
                        cursor.getInt(emailTypeIndex),
                        cursor.getString(emailLabelIndex),
                    ).toString()
                    person.emails += LabeledValue(address, label)
                }
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                    person.organization = cursor.getString(companyIndex)
                    person.jobTitle = cursor.getString(titleIndex)
                }
            }
        }
    }
    return people.mapNotNull { (key, person) ->
        ProviderContact(
            lookupKey = key,
            displayName = person.displayName,
            organization = person.organization,
            jobTitle = person.jobTitle,
            phones = person.phones,
            emails = person.emails,
        ).toImport()
    }
}

private class Accumulator {
    var displayName: String? = null
    var organization: String? = null
    var jobTitle: String? = null
    val phones = mutableListOf<LabeledValue>()
    val emails = mutableListOf<LabeledValue>()
}
