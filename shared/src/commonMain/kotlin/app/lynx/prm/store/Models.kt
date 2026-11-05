package app.lynx.prm.store

data class Contact(
    val id: String,
    val displayName: String,
    val organization: String?,
    val jobTitle: String?,
    val kind: String,
    val androidLookupKey: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deletedAt: Long?,
)

data class PhoneNumber(
    val id: String,
    val contactId: String,
    val raw: String,
    val normalized: String,
    val label: String?,
    val deletedAt: Long?,
)

data class EmailAddress(
    val id: String,
    val contactId: String,
    val address: String,
    val label: String?,
    val deletedAt: Long?,
)

data class Note(
    val id: String,
    val contactId: String,
    val body: String,
    val source: String,
    val direction: String?,
    val occurredAt: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deletedAt: Long?,
)

data class Tag(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class Reminder(
    val id: String,
    val contactId: String,
    val noteId: String?,
    val body: String,
    val dueAt: Long,
    val doneAt: Long?,
    val updatedAt: Long,
    val deletedAt: Long?,
)

data class PhoneImport(val raw: String, val label: String?)

data class EmailImport(val address: String, val label: String?)

data class ContactImport(
    val lookupKey: String,
    val displayName: String,
    val organization: String? = null,
    val jobTitle: String? = null,
    val phones: List<PhoneImport> = emptyList(),
    val emails: List<EmailImport> = emptyList(),
)
