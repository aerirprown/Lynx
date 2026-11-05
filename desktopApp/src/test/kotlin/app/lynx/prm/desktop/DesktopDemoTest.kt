package app.lynx.prm.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import app.lynx.prm.library.platformFiles
import app.lynx.prm.ui.FolderPicker
import app.lynx.prm.ui.InstallPrefs
import app.lynx.prm.ui.LynxApp
import app.lynx.prm.ui.LynxModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Phase 2 demo, driven through the real screens with no window. It runs on whichever
 * OS runs the tests, so `:desktopApp:test` is the demo on Windows and on Linux.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopDemoTest {
    private val files = platformFiles()

    private class Prefs(folder: String, prefix: String) : InstallPrefs {
        override val deviceId: String = "device-$prefix"
        override val deviceName: String = prefix
        override val workFolder: String = platformFiles().tempDirectory()
        override var libraryFolder: String? = folder
        override var prefix: String = prefix
        override var libraryId: String? = null
        override var phoneRegion: String = "US"
    }

    private fun newModel(prefs: Prefs) = LynxModel(prefs, FolderPicker { null })

    /** Starts the app, runs [steps] through its screens, and closes it as the window's X does. */
    private fun withApp(prefs: Prefs, steps: ComposeUiTest.() -> Unit) {
        val model = newModel(prefs)
        model.start()
        runDesktopComposeUiTest(width = 1100, height = 760) {
            setContent { LynxApp(model) }
            waitForIdle()
            steps()
            waitForIdle()
        }
        model.shutdown()
    }

    private fun ComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.fill(label: String, value: String) {
        onNode(hasSetTextAction() and hasText(label)).performTextInput(value)
        waitForIdle()
    }

    /** In the wide layout a note can show twice, in the list and on the contact. */
    private fun ComposeUiTest.see(text: String) {
        assertTrue(onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(), "\"$text\" is not on screen.")
    }

    /**
     * A name can show more than once: in a reminder row and the contact list, or behind a
     * dialog. The contact list comes after the reminders, and dialogs come last.
     */
    private fun ComposeUiTest.clickLast(text: String) {
        onAllNodesWithText(text).onLast().performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.addContact(name: String) {
        click("Add")
        fill("Name", name)
        click("Save")
    }

    private fun ComposeUiTest.addNote(body: String) {
        click("Add note")
        fill("Note", body)
        click("Save")
    }

    @Test
    fun aContactTagNoteAndReminderSurviveARestart() {
        val prefs = Prefs(files.tempDirectory(), "desk")
        withApp(prefs) {
            addContact("Dana Shah")
            fill("Tag", "Client")
            click("Add tag")
            addNote("Talked about the quote")
            click("Add reminder")
            fill("Reminder", "Send the quote")
            click("Save")
            see("Talked about the quote")
        }
        withApp(prefs) {
            clickLast("Dana Shah")
            see("Talked about the quote")
            see("Send the quote")
            see("Client")
        }
    }

    @Test
    fun twoInstallsOnOneFolderEachSeeBothNotes() {
        val folder = files.tempDirectory()
        val bob = Prefs(folder, "bob")
        val office = Prefs(folder, "office")
        withApp(bob) {
            addContact("Dana Shah")
            addNote("From Bob")
        }
        withApp(office) {
            click("Dana Shah")
            addNote("From the office")
        }
        withApp(bob) {
            click("Sync now")
            click("Dana Shah")
            see("From Bob")
            see("From the office")
        }
        assertEquals(listOf("bob_lynx.sqlite", "office_lynx.sqlite"), files.listNames(folder).sorted())
    }

    @Test
    fun thisIsTheSamePersonLeavesOneContactWithBothNotes() {
        val prefs = Prefs(files.tempDirectory(), "desk")
        withApp(prefs) {
            addContact("Dana Shah")
            addNote("First note")
            addContact("Dana S")
            addNote("Second note")
            click("This is the same person")
            clickLast("Dana Shah")
            click("Join")
            see("First note")
            see("Second note")
        }
    }
}
