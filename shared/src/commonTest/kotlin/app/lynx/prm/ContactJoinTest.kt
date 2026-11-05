package app.lynx.prm

import app.lynx.prm.contacts.ImportRecord
import app.lynx.prm.contacts.SeenContact
import app.lynx.prm.contacts.SeenValue
import app.lynx.prm.db.MutableClock
import app.lynx.prm.db.UuidIds
import app.lynx.prm.library.LibrarySession
import app.lynx.prm.library.platformFiles
import app.lynx.prm.store.ContactImport
import app.lynx.prm.store.EmailImport
import app.lynx.prm.store.PhoneImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NUMBER = "(650) 253-0000"

private class Device(folder: String, prefix: String, val clock: MutableClock = MutableClock(1_000)) {
    val record = ImportRecord()
    val session = LibrarySession(
        libraryFolder = folder,
        deviceId = "device-$prefix",
        deviceName = prefix,
        requestedPrefix = prefix,
        knownLibraryId = null,
        defaultRegion = "US",
        clock = clock,
        ids = UuidIds(),
    )
    val store get() = session.store()

    fun open() = apply { session.open() }
    fun sync() = apply { session.sync() }
    fun close() = session.close()
    fun at(millis: Long) = apply { clock.millis = millis }
    fun import(vararg rows: ContactImport) = apply { store.importContacts(rows.toList(), record) }
    fun contacts() = store.listContacts()
    fun only() = contacts().single()
}

private fun dana(key: String, name: String = "Dana", number: String? = NUMBER, email: String? = null) = ContactImport(
    lookupKey = key,
    displayName = name,
    phones = listOfNotNull(number?.let { PhoneImport(it, "mobile") }),
    emails = listOfNotNull(email?.let { EmailImport(it, "work") }),
)

class ContactJoinTest {
    private val files = platformFiles()

    @Test
    fun twoContactsWithTheSameLookupKeyMergeWithoutErrorAndKeepEveryNote() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneA.import(dana("shared-key", number = null))
        phoneB.import(dana("shared-key", number = null))
        phoneA.store.addNote(phoneA.only().id, "from a")
        phoneB.store.addNote(phoneB.only().id, "from b")

        phoneA.sync()
        phoneB.at(3_000).sync()
        phoneA.at(4_000).sync()

        for (phone in listOf(phoneA, phoneB)) {
            val notes = phone.store.visibleNotes(phone.only().id).map { it.body }.toSet()
            assertEquals(setOf("from a", "from b"), notes)
        }
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun theSameNameAndASharedNumberJoin() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a"))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open().import(dana("key-b", number = "+1 650 253 0000"))
        phoneB.sync()
        phoneA.at(3_000).sync()

        assertEquals(1, phoneA.contacts().size)
        assertEquals(1, phoneA.store.phones(phoneA.only().id).size)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun theSameNameAndASharedEmailJoinWhateverTheLetterCase() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a", number = null, email = "dana@acme.test"))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-b", name = "DANA", number = null, email = "Dana@Acme.test")).sync()

        assertEquals(1, phoneB.contacts().size)
        assertEquals(1, phoneB.store.emails(phoneB.only().id).size)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun aSharedNumberWithDifferentNamesStaysTwoContacts() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a"))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-b", name = "Sam Shah")).sync()

        assertEquals(setOf("Dana", "Sam Shah"), phoneB.contacts().map { it.displayName }.toSet())
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun aHandMadeContactOnlyJoinsByHandAndItsRowsMoveToTheSurvivor() {
        val folder = files.tempDirectory()
        val desktop = Device(folder, "desktop").open()
        val handMade = desktop.store.createContact("Dana", phones = listOf(PhoneImport(NUMBER, null)))
        desktop.store.addNote(handMade, "typed on the desktop")
        desktop.store.addTag(handMade, "Client")
        desktop.store.addReminder(handMade, "Send the quote", 9_000)
        desktop.sync()

        val phone = Device(folder, "phone", MutableClock(2_000)).open().import(dana("key-a"))
        phone.sync()
        assertEquals(2, phone.contacts().size, "A contact with no lookup key never joins automatically.")

        val imported = phone.contacts().first { it.androidLookupKey != null }.id
        val survivor = phone.store.joinContacts(handMade, imported)

        assertEquals(handMade, survivor, "The earliest contact survives.")
        assertEquals(1, phone.contacts().size)
        assertEquals("typed on the desktop", phone.store.visibleNotes(survivor).single().body)
        assertEquals("Client", phone.store.tagsFor(survivor).single().name)
        assertEquals(survivor, phone.store.openReminders().single().contactId)
        assertEquals(1, phone.store.phones(survivor).size, "The repeated number is removed.")

        phone.sync()
        desktop.at(3_000).sync()
        assertEquals(1, desktop.contacts().size)
        desktop.close()
        phone.close()
    }

    @Test
    fun aTagOnBothContactsIsKeptOnce() {
        val folder = files.tempDirectory()
        val device = Device(folder, "a").open()
        val first = device.store.createContact("Dana")
        val second = device.store.createContact("Dana Shah")
        device.store.addTag(first, "Client")
        device.store.addTag(second, "Client")

        val survivor = device.store.joinContacts(first, second)

        assertEquals(listOf("Client"), device.store.tagsFor(survivor).map { it.name })
        device.close()
    }

    @Test
    fun aChainOfSharedDetailsJoinsAllThree() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a", number = NUMBER, email = "a@acme.test"))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-b", number = NUMBER, email = "b@acme.test")).sync()
        val phoneC = Device(folder, "c", MutableClock(3_000)).open()
        phoneC.import(dana("key-c", number = "(415) 555-0100", email = "b@acme.test")).sync()

        assertEquals(1, phoneC.contacts().size)
        phoneA.close()
        phoneB.close()
        phoneC.close()
    }

    @Test
    fun mergingTheSameFilesInEitherOrderPicksTheSameSurvivor() {
        // Files merge in folder order, so the older contact's file is read first, then last.
        fun survivorWhenOlderFileIs(olderPrefix: String): Long {
            val folder = files.tempDirectory()
            val older = Device(folder, olderPrefix).open().import(dana("key-a"))
            val newer = Device(folder, "m", MutableClock(2_000)).open().import(dana("key-b"))
            older.sync()
            newer.at(2_500).sync()
            val reader = Device(folder, "reader", MutableClock(3_000)).open()
            val survivor = reader.only()
            assertEquals(survivor.id, Device(folder, "second", MutableClock(4_000)).open().only().id)
            listOf(older, newer, reader).forEach { it.close() }
            return survivor.createdAt
        }
        assertEquals(1_000, survivorWhenOlderFileIs("a"))
        assertEquals(1_000, survivorWhenOlderFileIs("z"))
    }

    @Test
    fun aNoteAddedBeforeTheDeviceSawTheJoinEndsUpOnTheSurvivor() {
        val folder = files.tempDirectory()
        val desktop = Device(folder, "desktop").open()
        val first = desktop.store.createContact("Dana")
        val second = desktop.store.createContact("Dana Shah")
        desktop.sync()

        val laptop = Device(folder, "laptop", MutableClock(2_000)).open()
        val survivor = desktop.at(3_000).store.joinContacts(first, second)
        val joined = if (survivor == first) second else first
        desktop.sync()

        // The laptop hasn't seen the join, and writes to the contact that is about to go away.
        laptop.store.addNote(joined, "written offline")
        laptop.at(4_000).sync()
        desktop.at(5_000).sync()

        assertEquals("written offline", desktop.store.visibleNotes(survivor).single().body)
        assertEquals("written offline", laptop.store.visibleNotes(survivor).single().body)
        desktop.close()
        laptop.close()
    }

    @Test
    fun anOlderDevicesEditDoesNotBringAJoinedContactBack() {
        val folder = files.tempDirectory()
        val desktop = Device(folder, "desktop").open()
        val first = desktop.store.createContact("Dana")
        val second = desktop.store.createContact("Dana Shah")
        desktop.sync()

        val laptop = Device(folder, "laptop", MutableClock(2_000)).open()
        val survivor = desktop.at(3_000).store.joinContacts(first, second)
        val joined = if (survivor == first) second else first
        desktop.sync()

        // A later edit normally wins, and would normally bring a deleted row back.
        laptop.at(9_000).store.updateContact(joined, "Dana Edited", null, null, "personal", emptyList(), emptyList())
        laptop.sync()
        desktop.at(10_000).sync()

        for (device in listOf(desktop, laptop)) {
            assertEquals(survivor, device.only().id)
        }
        desktop.close()
        laptop.close()
    }
}

class ImportRecordTest {
    private val files = platformFiles()

    @Test
    fun anImportWhoseKeySitsOnAJoinedContactUpdatesTheSurvivorAndCreatesNoContact() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a"))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open().import(dana("key-b"))
        phoneB.sync()
        val survivor = phoneB.only().id

        phoneB.at(3_000).import(dana("key-b", name = "Dana Shah"))

        assertEquals(survivor, phoneB.only().id)
        assertEquals("Dana Shah", phoneB.only().displayName)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun anImportDoesNotRemoveANumberAnotherPhoneAdded() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-a", number = NUMBER))
        phoneA.store.addNote(phoneA.only().id, "keep me")
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-a", number = "(415) 555-0100")).sync()
        assertEquals(2, phoneB.store.phones(phoneB.only().id).size)

        phoneB.at(3_000).import(dana("key-a", name = "Dana Shah", number = "(415) 555-0100"))

        assertEquals(2, phoneB.store.phones(phoneB.only().id).size)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun aNumberThisPhoneImportedIsRemovedWhenThePhoneDropsIt() {
        val device = Device(files.tempDirectory(), "a").open().import(dana("key-a", number = NUMBER))
        device.at(2_000).import(dana("key-a", number = null))
        assertEquals(emptyList(), device.store.phones(device.only().id))
        device.close()
    }

    @Test
    fun twoPhonesThatSpellANameDifferentlyDoNotOverwriteEachOther() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-1", name = "Dana", number = null))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-1", name = "Dana Shah", number = null)).sync()
        phoneA.at(3_000).sync()
        val updatedAt = phoneA.only().updatedAt

        // Every app start imports again. Neither phone's contacts changed, so nothing is written.
        repeat(3) { round ->
            phoneA.at(4_000L + round).import(dana("key-1", name = "Dana", number = null)).sync()
            phoneB.at(5_000L + round).import(dana("key-1", name = "Dana Shah", number = null)).sync()
        }

        assertEquals(updatedAt, phoneA.only().updatedAt)
        assertEquals("Dana", phoneB.only().displayName)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun aChangeOnThePhoneStillReachesTheLibrary() {
        val folder = files.tempDirectory()
        val phoneA = Device(folder, "a").open().import(dana("key-1", name = "Dana", number = null))
        phoneA.sync()
        val phoneB = Device(folder, "b", MutableClock(2_000)).open()
        phoneB.import(dana("key-1", name = "Dana", number = null)).sync()

        phoneA.at(3_000).import(dana("key-1", name = "Dana Shah", number = null)).sync()
        phoneB.at(4_000).sync()

        assertEquals("Dana Shah", phoneB.only().displayName)
        phoneA.close()
        phoneB.close()
    }

    @Test
    fun aFolderChangeImportsThePersonAgainEvenThoughTheRecordKnowsThem() {
        val first = Device(files.tempDirectory(), "a").open().import(dana("key-1"))
        val carried = ImportRecord.decode(first.record.encode())
        val second = Device(files.tempDirectory(), "a").open()
        second.store.importContacts(listOf(dana("key-1")), carried)
        assertEquals("Dana", second.only().displayName)
        first.close()
        second.close()
    }

    @Test
    fun theRecordReadsBackAsItWasWritten() {
        val record = ImportRecord()
        val seen = SeenContact(
            displayName = "Dana Shah",
            organization = "Acme, Inc",
            jobTitle = null,
            phones = listOf(SeenValue("(650) 253-0000", "mobile"), SeenValue("415", null)),
            emails = emptyList(),
        )
        record["key|1"] = seen
        record["key2"] = SeenContact("Sam", null, null, emptyList(), listOf(SeenValue("sam@acme.test", "work")))

        val decoded = ImportRecord.decode(record.encode())

        assertEquals(seen, decoded["key|1"])
        assertNotNull(decoded["key2"])
        assertNull(decoded["missing"])
        assertTrue(ImportRecord.decode(null)["key|1"] == null)
    }
}
