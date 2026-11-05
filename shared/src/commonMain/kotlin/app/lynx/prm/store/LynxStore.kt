package app.lynx.prm.store

import app.lynx.prm.db.Clock
import app.lynx.prm.library.Merger
import app.lynx.prm.db.Ids
import app.lynx.prm.db.LynxDatabase
import app.lynx.prm.db.epochMs
import app.lynx.prm.match.PhoneNumbers
import app.lynx.prm.match.foldCase
import app.lynx.prm.db.Contact as ContactRow
import app.lynx.prm.db.Email as EmailRow
import app.lynx.prm.db.Note as NoteRow
import app.lynx.prm.db.Phone as PhoneRow
import app.lynx.prm.db.Reminder as ReminderRow
import app.lynx.prm.db.Tag as TagRow

class LynxStore(
    internal val db: LynxDatabase,
    private val clock: Clock,
    private val ids: Ids,
    private val deviceId: String,
    private val defaultRegion: String,
) {
    fun createContact(
        displayName: String,
        organization: String? = null,
        jobTitle: String? = null,
        kind: String = "unspecified",
        phones: List<PhoneImport> = emptyList(),
        emails: List<EmailImport> = emptyList(),
    ): String {
        require(displayName.isNotBlank()) { "A contact needs a name." }
        require(kind in KINDS) { "Unknown contact kind." }
        val now = clock.epochMs()
        val id = ids.newId()
        db.lynxQueries.upsertContact(
            id = id,
            displayName = displayName.trim(),
            organization = organization?.trim()?.ifEmpty { null },
            jobTitle = jobTitle?.trim()?.ifEmpty { null },
            kind = kind,
            androidLookupKey = null,
            createdAt = now,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = null,
        )
        phones.forEach { insertPhone(id, it, now) }
        emails.forEach { insertEmail(id, it, now) }
        return id
    }

    fun deleteContact(id: String) {
        val current = db.lynxQueries.selectContact(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertContact(
            id = current.id,
            displayName = current.display_name,
            organization = current.organization,
            jobTitle = current.job_title,
            kind = current.kind,
            androidLookupKey = current.android_lookup_key,
            createdAt = current.created_at,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = now,
        )
    }

    fun contact(id: String): Contact? = db.lynxQueries.selectContact(id).executeAsOneOrNull()?.toModel()

    fun listContacts(): List<Contact> {
        val notes = db.lynxQueries.selectAllNotes().executeAsList().filter { it.deleted_at == null }
        val latest = notes.groupBy { it.contact_id }.mapValues { (_, rows) -> rows.maxOf { it.occurred_at } }
        return db.lynxQueries.selectAllContacts().executeAsList()
            .filter { it.deleted_at == null }
            .sortedWith(
                compareBy<ContactRow> { latest[it.id] == null }
                    .thenByDescending { latest[it.id] ?: Long.MIN_VALUE }
                    .thenBy { it.display_name.foldCase() },
            )
            .map { it.toModel() }
    }

    fun search(term: String): List<Contact> {
        if (term.isBlank()) return listContacts()
        val ids = db.lynxQueries.searchContactIds(term).executeAsList().toSet()
        return listContacts().filter { it.id in ids }
    }

    fun phones(contactId: String): List<PhoneNumber> =
        db.lynxQueries.selectPhonesForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .map { it.toModel() }

    fun emails(contactId: String): List<EmailAddress> =
        db.lynxQueries.selectEmailsForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .map { it.toModel() }

    fun meta(key: String): String? = db.lynxQueries.selectMeta(key).executeAsOneOrNull()

    fun updateContact(
        id: String,
        displayName: String,
        organization: String?,
        jobTitle: String?,
        kind: String,
        phones: List<PhoneImport>,
        emails: List<EmailImport>,
    ) {
        require(displayName.isNotBlank()) { "A contact needs a name." }
        require(kind in KINDS) { "Unknown contact kind." }
        val current = db.lynxQueries.selectContact(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertContact(
            id = current.id,
            displayName = displayName.trim(),
            organization = organization?.trim()?.ifEmpty { null },
            jobTitle = jobTitle?.trim()?.ifEmpty { null },
            kind = kind,
            androidLookupKey = current.android_lookup_key,
            createdAt = current.created_at,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = current.deleted_at,
        )
        syncPhones(id, phones, now)
        syncEmails(id, emails, now)
    }

    fun importContacts(rows: List<ContactImport>) {
        for (row in rows) {
            if (row.displayName.isBlank() && row.phones.isEmpty()) continue
            val existing = db.lynxQueries.selectContactByLookup(row.lookupKey).executeAsOneOrNull()
            val now = clock.epochMs()
            val contactId = if (existing == null) {
                val id = ids.newId()
                db.lynxQueries.upsertContact(
                    id = id,
                    displayName = row.displayName.trim().ifEmpty { row.phones.first().raw },
                    organization = row.organization,
                    jobTitle = row.jobTitle,
                    kind = "unspecified",
                    androidLookupKey = row.lookupKey,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = deviceId,
                    deletedAt = null,
                )
                id
            } else {
                db.lynxQueries.upsertContact(
                    id = existing.id,
                    displayName = row.displayName.trim().ifEmpty { existing.display_name },
                    organization = row.organization,
                    jobTitle = row.jobTitle,
                    kind = existing.kind,
                    androidLookupKey = existing.android_lookup_key,
                    createdAt = existing.created_at,
                    updatedAt = now,
                    updatedBy = deviceId,
                    deletedAt = existing.deleted_at,
                )
                existing.id
            }
            syncPhones(contactId, row.phones, now)
            syncEmails(contactId, row.emails, now)
        }
    }

    fun addNote(
        contactId: String,
        body: String,
        source: String = "manual",
        occurredAt: Long = clock.epochMs(),
        direction: String? = null,
        callNumberRaw: String? = null,
        callNumberNormalized: String? = null,
        reminderBody: String? = null,
        reminderDueAt: Long? = null,
    ): String {
        require(body.isNotBlank()) { "A note needs text." }
        require(source == "manual" || source == "call_prompt")
        require(direction == null || direction == "missed" || direction == "unknown")
        val now = clock.epochMs()
        val id = ids.newId()
        db.lynxQueries.upsertNote(
            id = id,
            contactId = contactId,
            body = body,
            source = source,
            callDirection = direction,
            callNumberRaw = callNumberRaw,
            callNumberNormalized = callNumberNormalized,
            occurredAt = occurredAt,
            createdAt = now,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = null,
        )
        if (!reminderBody.isNullOrBlank() && reminderDueAt != null) {
            addReminder(contactId, reminderBody, reminderDueAt, noteId = id)
        }
        return id
    }

    fun editNote(id: String, body: String, occurredAt: Long? = null) {
        require(body.isNotBlank()) { "A note needs text." }
        val current = db.lynxQueries.selectNote(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertNote(
            id = current.id,
            contactId = current.contact_id,
            body = body,
            source = current.source,
            callDirection = current.call_direction,
            callNumberRaw = current.call_number_raw,
            callNumberNormalized = current.call_number_normalized,
            occurredAt = occurredAt ?: current.occurred_at,
            createdAt = current.created_at,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = null,
        )
    }

    fun deleteNote(id: String) {
        val current = db.lynxQueries.selectNote(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertNote(
            id = current.id,
            contactId = current.contact_id,
            body = current.body,
            source = current.source,
            callDirection = current.call_direction,
            callNumberRaw = current.call_number_raw,
            callNumberNormalized = current.call_number_normalized,
            occurredAt = current.occurred_at,
            createdAt = current.created_at,
            updatedAt = now,
            updatedBy = deviceId,
            deletedAt = now,
        )
    }

    fun visibleNotes(contactId: String): List<Note> =
        db.lynxQueries.selectVisibleNotesForContact(contactId).executeAsList().map { it.toModel() }

    fun note(id: String): Note? = db.lynxQueries.selectNote(id).executeAsOneOrNull()?.toModel()

    fun addTag(contactId: String, name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A tag needs a name." }
        val normalized = trimmed.foldCase()
        val now = clock.epochMs()
        val existing = db.lynxQueries.selectAllTags().executeAsList()
            .filter { it.deleted_at == null && it.name.trim().foldCase() == normalized }
            .minWithOrNull(compareBy<TagRow> { it.created_at }.thenBy { it.id })
        val tagId = if (existing != null) {
            existing.id
        } else {
            val id = ids.newId()
            db.lynxQueries.upsertTag(id, trimmed, now, now, deviceId, null)
            id
        }
        val link = db.lynxQueries.selectContactTagsForContact(contactId).executeAsList()
            .firstOrNull { it.tag_id == tagId }
        if (link == null) {
            db.lynxQueries.upsertContactTag(ids.newId(), contactId, tagId, now, now, deviceId, null)
        } else if (link.deleted_at != null) {
            db.lynxQueries.upsertContactTag(link.id, link.contact_id, tagId, link.created_at, now, deviceId, null)
        }
        return tagId
    }

    fun removeTag(contactId: String, tagId: String) {
        val now = clock.epochMs()
        db.lynxQueries.selectContactTagsForContact(contactId).executeAsList()
            .filter { it.tag_id == tagId && it.deleted_at == null }
            .forEach { link ->
                db.lynxQueries.upsertContactTag(
                    link.id, link.contact_id, link.tag_id, link.created_at, now, deviceId, now,
                )
            }
    }

    fun deleteTag(id: String) {
        val tag = db.lynxQueries.selectTag(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.selectContactTagsForTag(id).executeAsList()
            .filter { it.deleted_at == null }
            .forEach { link ->
                db.lynxQueries.upsertContactTag(
                    link.id, link.contact_id, link.tag_id, link.created_at, now, deviceId, now,
                )
            }
        db.lynxQueries.upsertTag(tag.id, tag.name, tag.created_at, now, deviceId, now)
    }

    fun renameTag(id: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A tag needs a name." }
        val tag = db.lynxQueries.selectTag(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertTag(tag.id, trimmed, tag.created_at, now, deviceId, tag.deleted_at)
        Merger.collapseTags(db, now, deviceId)
    }

    fun visibleTags(): List<Tag> =
        db.lynxQueries.selectAllTags().executeAsList().filter { it.deleted_at == null }.map { it.toModel() }

    fun tagsFor(contactId: String): List<Tag> {
        val linked = db.lynxQueries.selectContactTagsForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .map { it.tag_id }
            .toSet()
        return db.lynxQueries.selectAllTags().executeAsList()
            .filter { it.deleted_at == null && it.id in linked }
            .map { it.toModel() }
    }

    fun addReminder(contactId: String, body: String, dueAt: Long, noteId: String? = null): String {
        require(body.isNotBlank()) { "A reminder needs text." }
        val now = clock.epochMs()
        val id = ids.newId()
        db.lynxQueries.upsertReminder(id, contactId, noteId, body, dueAt, null, now, now, deviceId, null)
        return id
    }

    fun markReminderDone(id: String) {
        val current = db.lynxQueries.selectReminder(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertReminder(
            current.id,
            current.contact_id,
            current.note_id,
            current.body,
            current.due_at,
            now,
            current.created_at,
            now,
            deviceId,
            current.deleted_at,
        )
    }

    fun updateReminder(id: String, body: String, dueAt: Long) {
        require(body.isNotBlank()) { "A reminder needs text." }
        val current = db.lynxQueries.selectReminder(id).executeAsOne()
        if (current.done_at != null || current.deleted_at != null) return
        val now = clock.epochMs()
        db.lynxQueries.upsertReminder(
            current.id, current.contact_id, current.note_id, body, dueAt, null,
            current.created_at, now, deviceId, null,
        )
    }

    fun deleteReminder(id: String) {
        val current = db.lynxQueries.selectReminder(id).executeAsOne()
        val now = clock.epochMs()
        db.lynxQueries.upsertReminder(
            current.id, current.contact_id, current.note_id, current.body, current.due_at, current.done_at,
            current.created_at, now, deviceId, now,
        )
    }

    fun openReminders(): List<Reminder> {
        val now = clock.epochMs()
        return db.lynxQueries.selectAllReminders().executeAsList()
            .filter { it.deleted_at == null && it.done_at == null }
            .sortedWith(compareBy<ReminderRow> { it.due_at >= now }.thenBy { it.due_at })
            .map { it.toModel() }
    }

    fun reminder(id: String): Reminder? = db.lynxQueries.selectReminder(id).executeAsOneOrNull()?.toModel()

    private fun syncPhones(contactId: String, imports: List<PhoneImport>, now: Long) {
        val existing = db.lynxQueries.selectPhonesForContact(contactId).executeAsList().toMutableList()
        val matched = mutableSetOf<String>()
        for (imported in imports) {
            val normalized = PhoneNumbers.normalize(imported.raw, defaultRegion)
            val row = existing.firstOrNull { it.id !in matched && PhoneNumbers.matches(it.normalized, normalized) }
            if (row == null) {
                insertPhone(contactId, imported, now)
            } else {
                matched += row.id
                db.lynxQueries.upsertPhone(
                    row.id, row.contact_id, imported.raw, normalized, imported.label,
                    row.created_at, now, deviceId, null,
                )
            }
        }
        existing.filter { it.id !in matched && it.deleted_at == null }.forEach { row ->
            db.lynxQueries.upsertPhone(
                row.id, row.contact_id, row.raw, row.normalized, row.label,
                row.created_at, now, deviceId, now,
            )
        }
    }

    private fun syncEmails(contactId: String, imports: List<EmailImport>, now: Long) {
        val existing = db.lynxQueries.selectEmailsForContact(contactId).executeAsList().toMutableList()
        val matched = mutableSetOf<String>()
        for (imported in imports) {
            val row = existing.firstOrNull {
                it.id !in matched && it.address.trim().foldCase() == imported.address.trim().foldCase()
            }
            if (row == null) {
                insertEmail(contactId, imported, now)
            } else {
                matched += row.id
                db.lynxQueries.upsertEmail(
                    row.id, row.contact_id, imported.address.trim(), imported.label,
                    row.created_at, now, deviceId, null,
                )
            }
        }
        existing.filter { it.id !in matched && it.deleted_at == null }.forEach { row ->
            db.lynxQueries.upsertEmail(
                row.id, row.contact_id, row.address, row.label, row.created_at, now, deviceId, now,
            )
        }
    }

    private fun insertPhone(contactId: String, phone: PhoneImport, now: Long) {
        db.lynxQueries.upsertPhone(
            ids.newId(),
            contactId,
            phone.raw,
            PhoneNumbers.normalize(phone.raw, defaultRegion),
            phone.label,
            now,
            now,
            deviceId,
            null,
        )
    }

    private fun insertEmail(contactId: String, email: EmailImport, now: Long) {
        db.lynxQueries.upsertEmail(
            ids.newId(), contactId, email.address.trim(), email.label, now, now, deviceId, null,
        )
    }

    private companion object {
        val KINDS = setOf("personal", "business", "unspecified")
    }
}

private fun ContactRow.toModel() = Contact(
    id, display_name, organization, job_title, kind, android_lookup_key,
    created_at, updated_at, updated_by, deleted_at,
)

private fun PhoneRow.toModel() = PhoneNumber(id, contact_id, raw, normalized, label, deleted_at)

private fun EmailRow.toModel() = EmailAddress(id, contact_id, address, label, deleted_at)

private fun NoteRow.toModel() = Note(
    id, contact_id, body, source, call_direction, occurred_at, updated_at, updated_by, deleted_at,
)

private fun TagRow.toModel() = Tag(id, name, created_at, updated_at, deleted_at)

private fun ReminderRow.toModel() = Reminder(
    id, contact_id, note_id, body, due_at, done_at, updated_at, deleted_at,
)
