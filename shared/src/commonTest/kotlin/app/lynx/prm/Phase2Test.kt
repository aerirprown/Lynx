package app.lynx.prm

import app.lynx.prm.db.MutableClock
import app.lynx.prm.db.UuidIds
import app.lynx.prm.library.platformFiles
import app.lynx.prm.ui.FolderPicker
import app.lynx.prm.ui.InstallPrefs
import app.lynx.prm.ui.LynxModel
import app.lynx.prm.ui.Place
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Phase2Test {
    @Test
    fun aContactTagNoteAndReminderSurviveARestart() {
        val files = platformFiles()
        val folder = files.tempDirectory()
        val prefs = MemoryPrefs(deviceId = "device-a", deviceName = "Bob", folder = folder, prefix = "bob")
        val clock = MutableClock(10_000)
        val model = LynxModel(prefs, FolderPicker { null }, files, clock, UuidIds())
        model.start()
        model.saveContact(null, "Dana", "Acme", "Buyer", "business", emptyList(), emptyList())
        val id = assertIs<Place.Person>(model.place).id
        model.addTag(id, "Client")
        model.saveNote(id, null, "Talked about the quote", 10_000, "Send the quote", 20_000)
        model.shutdown()

        val again = LynxModel(prefs, FolderPicker { null }, files, clock, UuidIds())
        again.start()
        assertEquals("Dana", again.contacts.single().displayName)
        assertEquals("Client", again.tags.single().name)
        assertEquals(listOf("Client"), again.personTags(id).map { it.name })
        assertEquals("Talked about the quote", again.notes(id).single().body)
        assertEquals("Send the quote", again.reminders.single().body)
        again.shutdown()
    }

    @Test
    fun aNoteThatWasNeverPublishedSurvivesARestart() {
        val files = platformFiles()
        val prefs = MemoryPrefs("device-a", "Bob", files.tempDirectory(), "bob")
        val clock = MutableClock(10_000)
        val model = LynxModel(prefs, FolderPicker { null }, files, clock, UuidIds())
        model.start()
        model.saveContact(null, "Dana", "", "", "personal", emptyList(), emptyList())
        val id = assertIs<Place.Person>(model.place).id
        model.saveNote(id, null, "Not published yet", 10_000, "", null)

        val again = LynxModel(prefs, FolderPicker { null }, files, clock, UuidIds())
        again.start()
        assertEquals("Not published yet", again.notes(id).single().body)
        again.shutdown()
    }

    @Test
    fun publishingLeavesNoCopyOfTheLibraryBehind() {
        val files = platformFiles()
        val prefs = MemoryPrefs("device-a", "Bob", files.tempDirectory(), "bob")
        val model = LynxModel(prefs, FolderPicker { null }, files, MutableClock(10_000), UuidIds())
        model.start()
        model.saveContact(null, "Dana", "", "", "personal", emptyList(), emptyList())
        model.shutdown()
        assertEquals(listOf("work.sqlite"), files.listNames(prefs.workFolder).filter { !it.startsWith("work.sqlite-") })
    }

    @Test
    fun aNewFolderDoesNotReceiveTheOldLibrary() {
        val files = platformFiles()
        val first = files.tempDirectory()
        val second = files.tempDirectory()
        val prefs = MemoryPrefs("device-a", "Bob", first, "bob")
        val clock = MutableClock(10_000)
        val model = LynxModel(prefs, FolderPicker { null }, files, clock, UuidIds())
        model.start()
        model.saveContact(null, "Dana", "", "", "personal", emptyList(), emptyList())
        model.switchFolder(second)
        model.startNewLibrary()
        assertEquals(emptyList(), model.contacts.map { it.displayName })
        model.shutdown()
    }

    @Test
    fun twoPrefixesInOneFolderMergeBothNotes() {
        val files = platformFiles()
        val folder = files.tempDirectory()
        val clock = MutableClock(10_000)
        val bob = MemoryPrefs("bob-device", "Bob", folder, "bob")
        val office = MemoryPrefs("office-device", "Office", folder, "office")

        val bobModel = LynxModel(bob, FolderPicker { null }, files, clock, UuidIds())
        bobModel.start()
        bobModel.saveContact(null, "Dana", "", "", "personal", emptyList(), emptyList())
        val id = assertIs<Place.Person>(bobModel.place).id
        bobModel.saveNote(id, null, "From Bob", 10_000, "", null)
        bobModel.shutdown()

        val officeModel = LynxModel(office, FolderPicker { null }, files, clock, UuidIds())
        officeModel.start()
        officeModel.saveNote(id, null, "From Office", 11_000, "", null)
        officeModel.shutdown()

        val bobAgain = LynxModel(bob, FolderPicker { null }, files, clock, UuidIds())
        bobAgain.start()
        assertEquals(setOf("From Bob", "From Office"), bobAgain.notes(id).map { it.body }.toSet())
        bobAgain.shutdown()
    }
}

private class MemoryPrefs(
    override val deviceId: String,
    override val deviceName: String,
    folder: String,
    prefix: String,
) : InstallPrefs {
    override val workFolder: String = platformFiles().tempDirectory()
    override var libraryFolder: String? = folder
    override var prefix: String = prefix
    override var libraryId: String? = null
    override var phoneRegion: String = "US"
}
