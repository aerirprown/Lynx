package app.lynx.prm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.lynx.prm.db.Clock
import app.lynx.prm.db.Ids
import app.lynx.prm.db.UuidIds
import app.lynx.prm.db.epochMs
import app.lynx.prm.library.LibrarySession
import app.lynx.prm.library.OpenResult
import app.lynx.prm.library.PlatformFiles
import app.lynx.prm.library.SkippedFile
import app.lynx.prm.library.platformFiles
import app.lynx.prm.match.lynxFileName
import app.lynx.prm.store.Contact
import app.lynx.prm.store.ContactImport
import app.lynx.prm.store.EmailImport
import app.lynx.prm.store.PhoneImport
import app.lynx.prm.store.Reminder
import app.lynx.prm.store.Tag
import kotlinx.datetime.Clock as DateClock
import kotlinx.datetime.Instant

interface InstallPrefs {
    val deviceId: String
    val deviceName: String
    var libraryFolder: String?
    var prefix: String
    var libraryId: String?
    var phoneRegion: String
}

fun interface FolderPicker {
    fun pick(): String?
}

sealed interface Place {
    data object Setup : Place
    data object Home : Place
    data class Person(val id: String) : Place
    data object Settings : Place
}

sealed interface Editor {
    data class ContactEditor(val id: String?) : Editor
    data class NoteEditor(val contactId: String, val noteId: String?) : Editor
    data class ReminderEditor(val contactId: String, val reminderId: String?) : Editor
    data class DeleteContact(val id: String, val name: String) : Editor
    data class PrefixClash(val fileName: String, val suggestion: String) : Editor
}

class WallClock : Clock {
    override fun now(): Instant = DateClock.System.now()
}

class PhoneAccess(
    contactsGranted: Boolean,
    notificationsGranted: Boolean,
    val requestContacts: () -> Unit,
    val requestNotifications: () -> Unit,
) {
    var contactsGranted by mutableStateOf(contactsGranted)
    var notificationsGranted by mutableStateOf(notificationsGranted)
    var contactsSkipped by mutableStateOf(false)
    var notificationsSkipped by mutableStateOf(false)
}

class LynxModel(
    private val prefs: InstallPrefs,
    private val pickFolder: FolderPicker,
    private val files: PlatformFiles = platformFiles(),
    private val clock: Clock = WallClock(),
    private val ids: Ids = UuidIds(),
) {
    var place by mutableStateOf<Place>(Place.Setup)
    var contacts by mutableStateOf<List<Contact>>(emptyList())
    var reminders by mutableStateOf<List<Reminder>>(emptyList())
    var tags by mutableStateOf<List<Tag>>(emptyList())
    var search by mutableStateOf("")
    var selectedTagIds by mutableStateOf<Set<String>>(emptySet())
    var status by mutableStateOf<String?>(null)
    var statusGood by mutableStateOf(false)
    var skipped by mutableStateOf<List<SkippedFile>>(emptyList())
    var editor by mutableStateOf<Editor?>(null)
    var banner by mutableStateOf<Reminder?>(null)
    var publishedAt by mutableStateOf<String?>(null)
    var otherFiles by mutableStateOf<List<String>>(emptyList())
    var blockedMessage by mutableStateOf<String?>(null)
    var phoneAccess by mutableStateOf<PhoneAccess?>(null)
    var dialNumber: ((String) -> Unit)? = null
    var pendingPerson: String? = null
    private var session: LibrarySession? = null
    private val announced = mutableSetOf<String>()

    val prefix: String get() = prefs.prefix
    var folder by mutableStateOf(prefs.libraryFolder)
        private set
    var region by mutableStateOf(prefs.phoneRegion)
        private set
    val fileName: String get() = session?.fileName ?: lynxFileName(prefs.prefix)

    fun start() {
        val folder = prefs.libraryFolder
        if (folder == null) place = Place.Setup else open(folder, prefs.prefix)
    }

    fun chooseFolder() {
        val path = pickFolder.pick() ?: return
        acceptFolder(path)
    }

    fun acceptFolder(path: String) {
        if (session == null) {
            prefs.libraryFolder = path
            folder = path
        } else {
            switchFolder(path)
        }
    }

    fun importDeviceContacts(rows: List<ContactImport>) {
        if (rows.isEmpty() || session == null) return
        edit { store()?.importContacts(rows) }
    }

    fun openFromNotification(id: String) {
        if (session == null) pendingPerson = id else place = Place.Person(id)
    }

    fun switchFolder(path: String) {
        session?.sync()
        session?.close()
        session = null
        prefs.libraryFolder = path
        folder = path
        open(path, prefs.prefix)
    }

    fun startNewLibrary() {
        prefs.libraryId = null
        blockedMessage = null
        val folder = prefs.libraryFolder ?: return
        open(folder, prefs.prefix)
    }

    fun usePrefix(raw: String) {
        val previous = session?.fileName
        val folder = prefs.libraryFolder
        session?.sync()
        session?.close()
        session = null
        prefs.prefix = raw
        if (folder != null) open(folder, raw)
        val current = session
        if (current != null && previous != null && previous != current.fileName && folder != null) {
            files.delete(files.child(folder, previous))
        }
    }

    fun updateRegion(value: String) {
        val next = value.trim().ifEmpty { "US" }
        prefs.phoneRegion = next
        region = next
    }

    fun openSettings() {
        reload()
        place = Place.Settings
    }

    fun openHome() {
        reload()
        place = Place.Home
    }

    fun openPerson(id: String) {
        place = Place.Person(id)
    }

    fun updateSearch(value: String) {
        search = value
        reload()
    }

    fun toggleTag(id: String) {
        selectedTagIds = if (id in selectedTagIds) selectedTagIds - id else selectedTagIds + id
        reload()
    }

    fun syncNow() {
        val result = session?.sync()
        if (result is OpenResult.PrefixInUse) {
            editor = Editor.PrefixClash(result.fileName, result.suggestedPrefix)
            return
        }
        reload()
        status = "Synced."
        statusGood = true
    }

    fun shutdown() {
        session?.sync()
        session?.close()
        session = null
    }

    fun checkDue() {
        val now = clock.epochMs()
        val due = reminders.firstOrNull { it.dueAt <= now && it.id !in announced } ?: return
        announced += due.id
        banner = due
    }

    fun dismissBanner() {
        banner = null
    }

    fun contactName(id: String): String = store()?.contact(id)?.displayName ?: "Someone"
    fun notePreview(id: String): String =
        store()?.visibleNotes(id)?.firstOrNull()?.body?.lineSequence()?.firstOrNull()?.ifBlank { "No notes yet" }
            ?: "No notes yet"

    fun isOverdue(reminder: Reminder): Boolean = reminder.dueAt < clock.epochMs()

    fun saveContact(
        id: String?,
        name: String,
        organization: String,
        title: String,
        kind: String,
        phones: List<PhoneImport>,
        emails: List<EmailImport>,
    ) = edit {
        val store = store() ?: return@edit
        if (id == null) {
            val created = store.createContact(name, organization, title, kind, phones, emails)
            place = Place.Person(created)
        } else {
            store.updateContact(id, name, organization, title, kind, phones, emails)
        }
        editor = null
    }

    fun deleteContact(id: String) = edit {
        store()?.deleteContact(id)
        editor = null
        place = Place.Home
    }

    fun saveNote(contactId: String, noteId: String?, body: String, occurredAt: Long, reminderBody: String, reminderDue: Long?) =
        edit {
            val store = store() ?: return@edit
            if (noteId == null) {
                store.addNote(
                    contactId = contactId,
                    body = body,
                    occurredAt = occurredAt,
                    reminderBody = reminderBody.ifBlank { null },
                    reminderDueAt = if (reminderBody.isBlank()) null else reminderDue,
                )
            } else {
                store.editNote(noteId, body, occurredAt)
                if (reminderBody.isNotBlank() && reminderDue != null) {
                    store.addReminder(contactId, reminderBody, reminderDue, noteId)
                }
            }
            editor = null
        }

    fun saveReminder(contactId: String, reminderId: String?, body: String, dueAt: Long) = edit {
        val store = store() ?: return@edit
        if (reminderId == null) store.addReminder(contactId, body, dueAt) else store.updateReminder(reminderId, body, dueAt)
        editor = null
    }

    fun completeReminder(id: String) = edit {
        store()?.markReminderDone(id)
        if (banner?.id == id) banner = null
    }

    fun deleteReminder(id: String) = edit {
        store()?.deleteReminder(id)
        editor = null
        if (banner?.id == id) banner = null
    }

    fun addTag(contactId: String, name: String) = edit { store()?.addTag(contactId, name) }
    fun removeTag(contactId: String, tagId: String) = edit { store()?.removeTag(contactId, tagId) }
    fun renameTag(id: String, name: String) = edit { store()?.renameTag(id, name) }
    fun deleteTag(id: String) = edit {
        store()?.deleteTag(id)
        selectedTagIds = selectedTagIds - id
    }

    fun person(id: String) = store()?.contact(id)
    fun phones(id: String) = store()?.phones(id).orEmpty()
    fun emails(id: String) = store()?.emails(id).orEmpty()
    fun notes(id: String) = store()?.visibleNotes(id).orEmpty()
    fun personTags(id: String) = store()?.tagsFor(id).orEmpty()
    fun personReminders(id: String) = reminders.filter { it.contactId == id }
    fun note(id: String) = store()?.note(id)
    fun reminder(id: String) = store()?.reminder(id)

    private fun open(folder: String, prefix: String) {
        val opening = LibrarySession(
            libraryFolder = folder,
            deviceId = prefs.deviceId,
            deviceName = prefs.deviceName,
            requestedPrefix = prefix,
            knownLibraryId = prefs.libraryId,
            defaultRegion = prefs.phoneRegion.ifBlank { "US" },
            clock = clock,
            ids = ids,
            files = files,
        )
        when (val result = opening.open()) {
            is OpenResult.Ready -> {
                session = opening
                prefs.libraryId = result.libraryId
                prefs.libraryFolder = folder
                this.folder = folder
                prefs.prefix = opening.prefix
                skipped = result.skipped
                blockedMessage = null
                editor = null
                reload()
                val pending = pendingPerson
                if (pending != null && store()?.contact(pending) != null) {
                    pendingPerson = null
                    place = Place.Person(pending)
                } else if (place == Place.Setup) {
                    place = Place.Home
                }
            }
            is OpenResult.FilesNotOnDevice -> {
                opening.close()
                session = null
                blockedMessage = result.message
                place = Place.Setup
            }
            is OpenResult.PrefixInUse -> {
                opening.close()
                session = null
                editor = Editor.PrefixClash(result.fileName, result.suggestedPrefix)
                place = Place.Setup
            }
        }
    }

    private fun reload() {
        val store = store() ?: return
        val found = if (search.isBlank()) store.listContacts() else store.search(search)
        val selected = selectedTagIds
        contacts = if (selected.isEmpty()) {
            found
        } else {
            found.filter { contact -> selected.all { tagId -> tagId in store.tagsFor(contact.id).map { it.id } } }
        }
        reminders = store.openReminders()
        tags = store.visibleTags()
        publishedAt = store.meta("published_at")
        val folder = prefs.libraryFolder
        val own = session?.fileName
        otherFiles = if (folder == null) {
            emptyList()
        } else {
            files.listNames(folder).filter { it.endsWith("_lynx.sqlite") && it != own }.sorted()
        }
    }

    private fun store() = session?.store()

    private inline fun edit(block: () -> Unit) {
        try {
            block()
            status = null
            reload()
        } catch (error: IllegalArgumentException) {
            status = error.message
            statusGood = false
        }
    }
}
