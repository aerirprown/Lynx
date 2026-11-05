package app.lynx.prm.library

import app.lynx.prm.db.LynxDatabase
import app.lynx.prm.db.Tag
import app.lynx.prm.match.foldCase

internal object Merger {
    fun mergePeer(local: LynxDatabase, peer: LynxDatabase) {
        val localTags = local.lynxQueries.selectAllTags().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllTags().executeAsList()) {
            val current = localTags[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertTag(row.id, row.name, row.created_at, row.updated_at, row.updated_by, row.deleted_at)
            }
        }

        val localContacts = local.lynxQueries.selectAllContacts().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllContacts().executeAsList()) {
            val current = localContacts[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertContact(
                    row.id, row.display_name, row.organization, row.job_title, row.kind, row.android_lookup_key,
                    row.created_at, row.updated_at, row.updated_by, row.deleted_at,
                )
            }
        }

        val localPhones = local.lynxQueries.selectAllPhones().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllPhones().executeAsList()) {
            val current = localPhones[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertPhone(
                    row.id, row.contact_id, row.raw, row.normalized, row.label,
                    row.created_at, row.updated_at, row.updated_by, row.deleted_at,
                )
            }
        }

        val localEmails = local.lynxQueries.selectAllEmails().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllEmails().executeAsList()) {
            val current = localEmails[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertEmail(
                    row.id, row.contact_id, row.address, row.label,
                    row.created_at, row.updated_at, row.updated_by, row.deleted_at,
                )
            }
        }

        val localNotes = local.lynxQueries.selectAllNotes().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllNotes().executeAsList()) {
            val current = localNotes[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertNote(
                    row.id, row.contact_id, row.body, row.source, row.call_direction, row.call_number_raw,
                    row.call_number_normalized, row.occurred_at, row.created_at, row.updated_at, row.updated_by,
                    row.deleted_at,
                )
            }
        }

        val localLinks = local.lynxQueries.selectAllContactTags().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllContactTags().executeAsList()) {
            val current = localLinks[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertContactTag(
                    row.id, row.contact_id, row.tag_id, row.created_at, row.updated_at, row.updated_by, row.deleted_at,
                )
            }
        }

        val localReminders = local.lynxQueries.selectAllReminders().executeAsList().associateBy { it.id }
        for (row in peer.lynxQueries.selectAllReminders().executeAsList()) {
            val current = localReminders[row.id]
            if (choose(current, row, { it.updated_at }, { it.updated_by }) !== current) {
                local.lynxQueries.upsertReminder(
                    row.id, row.contact_id, row.note_id, row.body, row.due_at, row.done_at,
                    row.created_at, row.updated_at, row.updated_by, row.deleted_at,
                )
            }
        }
    }

    fun collapseTags(local: LynxDatabase, now: Long, deviceId: String) {
        val visible = local.lynxQueries.selectAllTags().executeAsList().filter { it.deleted_at == null }
        for (group in visible.groupBy { it.name.trim().foldCase() }.values) {
            if (group.size < 2) continue
            val survivor = group.minWith(compareBy<Tag> { it.created_at }.thenBy { it.id })
            for (loser in group) {
                if (loser.id == survivor.id) continue
                val links = local.lynxQueries.selectContactTagsForTag(loser.id).executeAsList()
                for (link in links) {
                    if (link.deleted_at != null) continue
                    val already = local.lynxQueries.selectContactTagsForContact(link.contact_id).executeAsList()
                        .any { it.deleted_at == null && it.tag_id == survivor.id }
                    if (already) {
                        local.lynxQueries.upsertContactTag(
                            link.id, link.contact_id, link.tag_id, link.created_at, now, deviceId, now,
                        )
                    } else {
                        local.lynxQueries.updateContactTagTarget(survivor.id, now, deviceId, link.id)
                    }
                }
                local.lynxQueries.upsertTag(loser.id, loser.name, loser.created_at, now, deviceId, now)
            }
        }
    }
}

private fun <T> choose(local: T?, incoming: T, updatedAt: (T) -> Long, updatedBy: (T) -> String): T {
    if (local == null) return incoming
    val compared = updatedAt(incoming).compareTo(updatedAt(local))
    if (compared > 0) return incoming
    if (compared < 0) return local
    return if (updatedBy(incoming) > updatedBy(local)) incoming else local
}
