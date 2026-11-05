package app.lynx.prm

import app.lynx.prm.contacts.ImportRecord
import app.lynx.prm.contacts.LabeledValue
import app.lynx.prm.contacts.ProviderContact
import app.lynx.prm.contacts.toImport
import app.lynx.prm.db.MutableClock
import app.lynx.prm.db.UuidIds
import app.lynx.prm.library.LibrarySession
import app.lynx.prm.library.platformFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Phase3Test {
    @Test
    fun aProviderRowBecomesAContactAndASecondImportKeepsThePhoneAndTheNote() {
        val files = platformFiles()
        val session = LibrarySession(
            libraryFolder = files.tempDirectory(),
            deviceId = "device-bob",
            deviceName = "Bob",
            requestedPrefix = "bob",
            knownLibraryId = null,
            defaultRegion = "US",
            clock = MutableClock(100),
            ids = UuidIds(),
            files = files,
        )
        session.open()
        val store = session.store()
        val first = ProviderContact(
            lookupKey = "look-1",
            displayName = "Dana",
            organization = null,
            jobTitle = null,
            phones = listOf(LabeledValue("(650) 253-0000", "mobile")),
            emails = emptyList(),
        ).toImport()!!
        val record = ImportRecord()
        store.importContacts(listOf(first), record)
        val contactId = store.listContacts().single().id
        val phoneId = store.phones(contactId).single().id
        store.addNote(contactId, "met at the office")

        val second = ProviderContact(
            lookupKey = "look-1",
            displayName = "Dana Shah",
            organization = "Acme",
            jobTitle = "Buyer",
            phones = listOf(LabeledValue("+1 650-253-0000", "work")),
            emails = listOf(LabeledValue("dana@acme.test", "work")),
        ).toImport()!!
        store.importContacts(listOf(second), record)

        assertEquals(phoneId, store.phones(contactId).single().id)
        assertEquals("work", store.phones(contactId).single().label)
        assertEquals("Dana Shah", store.contact(contactId)?.displayName)
        assertEquals("Acme", store.contact(contactId)?.organization)
        assertEquals("met at the office", store.visibleNotes(contactId).single().body)
        assertEquals("dana@acme.test", store.emails(contactId).single().address)
        session.close()
    }

    @Test
    fun aProviderRowWithNoNameAndNoPhoneIsSkipped() {
        val row = ProviderContact(
            lookupKey = "look-empty",
            displayName = "  ",
            organization = "Acme",
            jobTitle = null,
            phones = listOf(LabeledValue("   ", null)),
            emails = listOf(LabeledValue("a@b.test", null)),
        )
        assertNull(row.toImport())
    }
}
