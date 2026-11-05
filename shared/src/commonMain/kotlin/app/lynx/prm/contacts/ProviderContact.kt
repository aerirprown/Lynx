package app.lynx.prm.contacts

import app.lynx.prm.store.ContactImport
import app.lynx.prm.store.EmailImport
import app.lynx.prm.store.PhoneImport

data class LabeledValue(val value: String, val label: String?)

data class ProviderContact(
    val lookupKey: String,
    val displayName: String?,
    val organization: String?,
    val jobTitle: String?,
    val phones: List<LabeledValue>,
    val emails: List<LabeledValue>,
)

fun ProviderContact.toImport(): ContactImport? {
    if (lookupKey.isBlank()) return null
    val phones = phones
        .filter { it.value.isNotBlank() }
        .distinctBy { it.value.filter { char -> char.isDigit() } }
        .map { PhoneImport(it.value, it.label?.trim()?.ifEmpty { null }) }
    val emails = emails
        .filter { it.value.isNotBlank() }
        .distinctBy { it.value.trim().lowercase() }
        .map { EmailImport(it.value.trim(), it.label?.trim()?.ifEmpty { null }) }
    val name = displayName?.trim().orEmpty()
    if (name.isBlank() && phones.isEmpty()) return null
    return ContactImport(
        lookupKey = lookupKey,
        displayName = name,
        organization = organization?.trim()?.ifEmpty { null },
        jobTitle = jobTitle?.trim()?.ifEmpty { null },
        phones = phones,
        emails = emails,
    )
}
