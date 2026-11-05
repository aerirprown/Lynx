package app.lynx.prm.library

import app.lynx.prm.db.Contact
import app.lynx.prm.db.LynxDatabase
import app.lynx.prm.db.Tag
import app.lynx.prm.match.PhoneNumbers
import app.lynx.prm.match.foldCase

internal object Merger {
    fun mergePeer(local: LynxDatabase, peer: LynxDatabase) = local.transaction {
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
            if (chooseContact(current, row) !== current) {
                local.lynxQueries.upsertContact(
                    row.id, row.display_name, row.organization, row.job_title, row.kind, row.android_lookup_key,
                    row.merged_into, row.created_at, row.updated_at, row.updated_by, row.deleted_at,
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

    /** Joins contacts that are clearly one person, then rescues rows left on a joined contact. */
    fun joinContacts(local: LynxDatabase, now: Long, deviceId: String) = local.transaction {
        val imported = local.lynxQueries.selectAllContacts().executeAsList()
            .filter { it.deleted_at == null && it.android_lookup_key != null }
        for (group in samePersonGroups(local, imported)) {
            joinGroup(local, group, now, deviceId)
        }
        moveStrandedRows(local, now, deviceId)
    }

    /** Joins these contacts and returns the survivor's id. Also used by "This is the same person". */
    fun joinGroup(local: LynxDatabase, group: List<Contact>, now: Long, deviceId: String): String {
        val survivor = group.minWith(compareBy<Contact> { it.created_at }.thenBy { it.id })
        for (other in group) {
            if (other.id == survivor.id) continue
            moveRows(local, other.id, survivor.id, now, deviceId)
            local.lynxQueries.markContactJoined(survivor.id, now, deviceId, other.id)
        }
        removeRepeats(local, survivor.id, now, deviceId)
        return survivor.id
    }

    fun endOfChain(local: LynxDatabase, id: String): String {
        val visited = mutableSetOf(id)
        var at = id
        while (true) {
            val next = local.lynxQueries.selectContact(at).executeAsOneOrNull()?.merged_into ?: return at
            if (!visited.add(next)) return at
            at = next
        }
    }

    private fun samePersonGroups(local: LynxDatabase, contacts: List<Contact>): List<List<Contact>> {
        val parent = contacts.associateTo(mutableMapOf()) { it.id to it.id }
        fun root(id: String): String {
            var at = id
            while (parent.getValue(at) != at) at = parent.getValue(at)
            return at
        }
        fun link(a: String, b: String) {
            parent[root(a)] = root(b)
        }

        for (sameKey in contacts.groupBy { it.android_lookup_key }.values) {
            sameKey.drop(1).forEach { link(sameKey.first().id, it.id) }
        }

        val phones = local.lynxQueries.selectAllPhones().executeAsList()
            .filter { it.deleted_at == null }
            .groupBy({ it.contact_id }, { it.normalized })
        val emails = local.lynxQueries.selectAllEmails().executeAsList()
            .filter { it.deleted_at == null }
            .groupBy({ it.contact_id }, { it.address.trim().foldCase() })
        fun shareAWayToReachThem(a: String, b: String): Boolean {
            val theirPhones = phones[b].orEmpty()
            if (phones[a].orEmpty().any { mine -> theirPhones.any { PhoneNumbers.matches(mine, it) } }) return true
            return emails[a].orEmpty().any { it in emails[b].orEmpty() }
        }
        for (sameName in contacts.groupBy { it.display_name.trim().foldCase() }.values) {
            for ((index, first) in sameName.withIndex()) {
                for (second in sameName.drop(index + 1)) {
                    if (shareAWayToReachThem(first.id, second.id)) link(first.id, second.id)
                }
            }
        }
        return contacts.groupBy { root(it.id) }.values.filter { it.size > 1 }
    }

    private fun moveRows(local: LynxDatabase, from: String, to: String, now: Long, deviceId: String) {
        local.lynxQueries.movePhones(to, now, deviceId, from)
        local.lynxQueries.moveEmails(to, now, deviceId, from)
        local.lynxQueries.moveNotes(to, now, deviceId, from)
        local.lynxQueries.moveContactTags(to, now, deviceId, from)
        local.lynxQueries.moveReminders(to, now, deviceId, from)
    }

    private fun removeRepeats(local: LynxDatabase, contactId: String, now: Long, deviceId: String) {
        val keptPhones = mutableListOf<String>()
        local.lynxQueries.selectPhonesForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .forEach { phone ->
                if (keptPhones.any { PhoneNumbers.matches(it, phone.normalized) }) {
                    local.lynxQueries.softDeletePhone(now, deviceId, phone.id)
                } else {
                    keptPhones += phone.normalized
                }
            }
        val keptEmails = mutableSetOf<String>()
        local.lynxQueries.selectEmailsForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .forEach { email ->
                if (!keptEmails.add(email.address.trim().foldCase())) {
                    local.lynxQueries.softDeleteEmail(now, deviceId, email.id)
                }
            }
        val keptTags = mutableSetOf<String>()
        local.lynxQueries.selectContactTagsForContact(contactId).executeAsList()
            .filter { it.deleted_at == null }
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .forEach { link ->
                if (!keptTags.add(link.tag_id)) {
                    local.lynxQueries.softDeleteContactTag(now, deviceId, link.id)
                }
            }
    }

    private fun moveStrandedRows(local: LynxDatabase, now: Long, deviceId: String) {
        val joined = local.lynxQueries.selectAllContacts().executeAsList().filter { it.merged_into != null }
        val survivors = mutableSetOf<String>()
        for (contact in joined) {
            val survivor = endOfChain(local, contact.id)
            if (survivor == contact.id) continue
            moveRows(local, contact.id, survivor, now, deviceId)
            survivors += survivor
        }
        survivors.forEach { removeRepeats(local, it, now, deviceId) }
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

private fun chooseContact(local: Contact?, incoming: Contact): Contact = when {
    local == null -> incoming
    local.merged_into != null && incoming.merged_into == null -> local
    local.merged_into == null && incoming.merged_into != null -> incoming
    else -> choose(local, incoming, { it.updated_at }, { it.updated_by })
}

private fun <T> choose(local: T?, incoming: T, updatedAt: (T) -> Long, updatedBy: (T) -> String): T {
    if (local == null) return incoming
    val compared = updatedAt(incoming).compareTo(updatedAt(local))
    if (compared > 0) return incoming
    if (compared < 0) return local
    return if (updatedBy(incoming) > updatedBy(local)) incoming else local
}
