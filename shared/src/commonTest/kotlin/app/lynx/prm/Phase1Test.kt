package app.lynx.prm

import app.lynx.prm.contacts.ImportRecord
import app.lynx.prm.db.MutableClock
import app.lynx.prm.db.UuidIds
import app.lynx.prm.library.LibrarySession
import app.lynx.prm.library.OpenResult
import app.lynx.prm.library.isSqliteDatabase
import app.lynx.prm.library.platformFiles
import app.lynx.prm.match.CallPromptController
import app.lynx.prm.match.IncomingNotification
import app.lynx.prm.match.MatchableContact
import app.lynx.prm.match.PhoneNumbers
import app.lynx.prm.match.lynxFileName
import app.lynx.prm.match.matchCaller
import app.lynx.prm.match.sanitizePrefix
import app.lynx.prm.store.ContactImport
import app.lynx.prm.store.PhoneImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneNumberTest {
    @Test
    fun normalizesToE164AndFallsBackWhenParsingFails() {
        assertEquals("+16502530000", PhoneNumbers.normalize("(650) 253-0000", "US"))
        assertEquals("12", PhoneNumbers.normalize("abc12", "US"))
        assertEquals("+99", PhoneNumbers.normalize("+abc99", "US"))
    }

    @Test
    fun matchesEqualNumbersOrTheLastTenDigits() {
        assertTrue(PhoneNumbers.matches("+16502530000", "+16502530000"))
        assertTrue(PhoneNumbers.matches("+19995550100", "9995550100"))
        assertFalse(PhoneNumbers.matches("+19995550100", "9995550101"))
    }
}

class CallerMatchTest {
    private val dana = MatchableContact("1", "Dana Shah", listOf("+16502530000"))

    @Test
    fun nameMatchIsCaseInsensitiveAndDoesNotMatchASubstring() {
        assertEquals(listOf(dana), matchCaller("dana shah", listOf(dana), "US"))
        assertTrue(matchCaller("Dana", listOf(dana), "US").isEmpty())
    }

    @Test
    fun genericTitlesDoNotMatch() {
        val ongoing = MatchableContact("2", "Ongoing call", emptyList())
        assertTrue(matchCaller("Ongoing call", listOf(ongoing, dana), "US").isEmpty())
        assertTrue(matchCaller("  ", listOf(dana), "US").isEmpty())
    }

    @Test
    fun phoneTitleMatchesTheLastTenDigits() {
        assertEquals(listOf(dana), matchCaller("(650) 253-0000", listOf(dana), "US"))
    }
}

class CallPromptTest {
    @Test
    fun removalUnderTwoSecondsDoesNotAsk() {
        val controller = controller()
        val notification = call("Dana Shah", postedAt = 0)
        controller.onPosted(notification, nowEpochMs = 0)
        assertNull(controller.onRemoved(notification.key, nowEpochMs = 1_500))
    }

    @Test
    fun missedCallWithinSixtySecondsDoesNotAskAgain() {
        val controller = controller()
        val notification = call("Dana Shah", postedAt = 0)
        controller.onPosted(notification, nowEpochMs = 0)
        assertNotNull(controller.onRemoved(notification.key, nowEpochMs = 3_000))
        val missed = call("Dana Shah", key = "missed", category = "missed_call", postedAt = 10_000)
        assertNull(controller.onPosted(missed, nowEpochMs = 10_000))
    }

    @Test
    fun notNowWritesNoNote() {
        val folder = platformFiles().tempDirectory()
        val session = session(folder, "bob", "device-bob", MutableClock(1_000))
        assertIs<OpenResult.Ready>(session.open())
        val contactId = session.store().createContact("Dana Shah")
        val controller = CallPromptController(
            contacts = { listOf(MatchableContact(contactId, "Dana Shah", emptyList())) },
            defaultRegion = "US",
        )
        val notification = call("Dana Shah", postedAt = 0)
        controller.onPosted(notification, nowEpochMs = 0)
        val prompt = controller.onRemoved(notification.key, nowEpochMs = 3_000)
        assertNotNull(prompt)
        controller.notNow()
        assertTrue(session.store().visibleNotes(contactId).isEmpty())
        controller.confirm(prompt, contactId, "Talked about the quote", session.store())
        assertEquals(1, session.store().visibleNotes(contactId).size)
        session.close()
    }

    private fun controller() = CallPromptController(contacts = { emptyList() }, defaultRegion = "US")

    private fun call(
        title: String,
        key: String = "call-1",
        category: String = "call",
        postedAt: Long,
    ) = IncomingNotification(
        key = key,
        packageName = "com.google.android.dialer",
        category = category,
        title = title,
        text = "",
        postedAtEpochMs = postedAt,
    )
}

class PrefixTest {
    @Test
    fun sanitizesTheDeviceNameIntoTheFileName() {
        assertEquals("bob", sanitizePrefix("Bob"))
        assertEquals("bob_lynx.sqlite", lynxFileName("Bob"))
        assertEquals("bobs_pixel", sanitizePrefix("Bob's Pixel"))
        assertEquals("lynx", sanitizePrefix("   "))
        assertEquals("lynx_lynx.sqlite", lynxFileName("***"))
    }
}

class LibraryTest {
    private val files = platformFiles()

    @Test
    fun openOnAnEmptyFolderCreatesThePrefixedFile() {
        val folder = files.tempDirectory()
        val session = session(folder, "Bob", "device-bob", MutableClock(1_000))
        val opened = session.open()
        assertIs<OpenResult.Ready>(opened)
        val path = files.child(folder, "bob_lynx.sqlite")
        assertTrue(files.exists(path))
        assertTrue(files.read(path).isSqliteDatabase())
        session.close()
    }

    @Test
    fun openSkipsAZeroBytePeer() {
        val folder = files.tempDirectory()
        val placeholder = files.child(folder, "other_lynx.sqlite")
        files.write(placeholder, ByteArray(0))
        val session = session(folder, "bob", "device-bob", MutableClock(1_000))
        val opened = session.open()
        assertIs<OpenResult.Ready>(opened)
        assertEquals(0L, files.size(placeholder))
        assertEquals(listOf("other_lynx.sqlite"), opened.skipped.map { it.name })
        assertTrue(files.read(placeholder).isEmpty())
        session.close()
    }

    @Test
    fun notesFromTwoDevicesBothSurvive() {
        val folder = files.tempDirectory()
        val bob = session(folder, "bob", "device-bob", MutableClock(1_000))
        bob.open()
        val contactId = bob.store().createContact("Dana Shah")
        bob.store().addNote(contactId, "from bob")
        bob.publish()

        val office = session(folder, "office", "device-office", MutableClock(2_000))
        office.open()
        office.store().addNote(contactId, "from office")
        office.publish()

        bob.sync()
        val bodies = bob.store().visibleNotes(contactId).map { it.body }.toSet()
        assertEquals(setOf("from bob", "from office"), bodies)
        bob.close()
        office.close()
    }

    @Test
    fun newerNoteWinsAndEqualTimestampsUseUpdatedBy() {
        val folder = files.tempDirectory()
        val earlier = MutableClock(5_000)
        val bob = session(folder, "bob", "aaa", earlier)
        bob.open()
        val contactId = bob.store().createContact("Dana Shah")
        val noteId = bob.store().addNote(contactId, "from-a")
        bob.publish()

        val office = session(folder, "office", "mmm", MutableClock(5_000))
        office.open()
        office.store().editNote(noteId, "from-m")
        office.publish()
        bob.sync()
        assertEquals("from-m", bob.store().note(noteId)?.body)
        bob.close()
        office.close()
    }

    @Test
    fun laterDeleteHidesARowAndLaterEditBringsItBack() {
        val folder = files.tempDirectory()
        val clockA = MutableClock(100)
        val bob = session(folder, "bob", "device-bob", clockA)
        bob.open()
        val contactId = bob.store().createContact("Dana Shah")
        val noteId = bob.store().addNote(contactId, "hello")
        bob.publish()

        val office = session(folder, "office", "device-office", MutableClock(200))
        office.open()
        office.store().deleteNote(noteId)
        office.publish()
        bob.sync()
        assertTrue(bob.store().visibleNotes(contactId).isEmpty())
        assertNotNull(bob.store().note(noteId)?.deletedAt)

        val other = files.tempDirectory()
        val maker = session(other, "bob", "device-bob", MutableClock(100))
        maker.open()
        val person = maker.store().createContact("Dana Shah")
        val id = maker.store().addNote(person, "hello")
        maker.publish()
        val editor = session(other, "office", "device-office", MutableClock(200))
        editor.open()
        editor.store().deleteNote(id)
        editor.publish()
        maker.clockForward(300)
        maker.store().editNote(id, "back")
        maker.publish()
        editor.sync()
        assertEquals("back", editor.store().note(id)?.body)
        assertNull(editor.store().note(id)?.deletedAt)
        bob.close()
        office.close()
        maker.close()
        editor.close()
    }

    @Test
    fun tagsWithTheSameNameCollapseAndLinksFollow() {
        val origin = files.tempDirectory()
        val bob = session(origin, "bob", "device-bob", MutableClock(500))
        bob.open()
        val contactId = bob.store().createContact("Dana Shah")
        bob.publish()
        val snapshot = files.tempDirectory()
        files.write(files.child(snapshot, "bob_lynx.sqlite"), files.read(files.child(origin, "bob_lynx.sqlite")))

        bob.store().addTag(contactId, "Client")
        bob.publish()

        val office = session(snapshot, "office", "device-office", MutableClock(100))
        office.open()
        office.store().addTag(contactId, "client")
        office.publish()

        val shared = files.tempDirectory()
        files.write(files.child(shared, "bob_lynx.sqlite"), files.read(files.child(origin, "bob_lynx.sqlite")))
        files.write(files.child(shared, "office_lynx.sqlite"), files.read(files.child(snapshot, "office_lynx.sqlite")))
        val reader = session(shared, "reader", "device-reader", MutableClock(900))
        reader.open()
        assertEquals(1, reader.store().visibleTags().size)
        assertEquals(listOf("client"), reader.store().tagsFor(contactId).map { it.name })
        bob.close()
        office.close()
        reader.close()
    }

    @Test
    fun aDoneReminderStaysDoneAgainstAnOlderOpenCopy() {
        val folder = files.tempDirectory()
        val bob = session(folder, "bob", "device-bob", MutableClock(100))
        bob.open()
        val contactId = bob.store().createContact("Dana Shah")
        val reminderId = bob.store().addReminder(contactId, "Send the quote", dueAt = 9_000)
        bob.publish()
        val office = session(folder, "office", "device-office", MutableClock(200))
        office.open()
        office.store().markReminderDone(reminderId)
        office.publish()
        bob.sync()
        assertNotNull(bob.store().reminder(reminderId)?.doneAt)
        bob.close()
        office.close()
    }

    @Test
    fun publishIsRefusedWhenTheFileBelongsToAnotherDevice() {
        val folder = files.tempDirectory()
        val owner = session(folder, "bob", "device-owner", MutableClock(100))
        owner.open()
        owner.close()
        val path = files.child(folder, "bob_lynx.sqlite")
        val before = files.read(path)
        val other = session(folder, "bob", "device-other", MutableClock(200))
        val opened = other.open()
        assertIs<OpenResult.PrefixInUse>(opened)
        assertEquals("bob_lynx.sqlite", opened.fileName)
        assertTrue(before.contentEquals(files.read(path)))
        other.close()
    }

    @Test
    fun importingTheSameNumberKeepsThePhoneRowAndTheNote() {
        val folder = files.tempDirectory()
        val session = session(folder, "bob", "device-bob", MutableClock(100))
        session.open()
        val store = session.store()
        val record = ImportRecord()
        store.importContacts(
            listOf(
                ContactImport(
                    lookupKey = "look-1",
                    displayName = "Dana",
                    phones = listOf(PhoneImport("(650) 253-0000", "mobile")),
                ),
            ),
            record,
        )
        val contactId = store.listContacts().single().id
        val phoneId = store.phones(contactId).single().id
        store.addNote(contactId, "met at the office")
        store.importContacts(
            listOf(
                ContactImport(
                    lookupKey = "look-1",
                    displayName = "Dana Shah",
                    phones = listOf(PhoneImport("+1 650-253-0000", "work")),
                ),
            ),
            record,
        )
        assertEquals(phoneId, store.phones(contactId).single().id)
        assertEquals("work", store.phones(contactId).single().label)
        assertEquals("Dana Shah", store.contact(contactId)?.displayName)
        assertEquals("met at the office", store.visibleNotes(contactId).single().body)
        session.close()
    }
}

private fun session(folder: String, prefix: String, deviceId: String, clock: MutableClock) = object {
    val library = LibrarySession(
        libraryFolder = folder,
        deviceId = deviceId,
        deviceName = prefix,
        requestedPrefix = prefix,
        knownLibraryId = null,
        defaultRegion = "US",
        clock = clock,
        ids = UuidIds(),
    )
    fun open() = library.open()
    fun publish() = library.publish()
    fun sync() = library.sync()
    fun store() = library.store()
    fun close() = library.close()
    fun clockForward(millis: Long) {
        clock.millis = millis
    }
}
