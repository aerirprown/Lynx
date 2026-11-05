package app.lynx.prm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.lynx.prm.db.epochMs
import app.lynx.prm.match.lynxFileName
import app.lynx.prm.store.Contact
import app.lynx.prm.store.EmailImport
import app.lynx.prm.store.PhoneImport
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val gap = 12.dp

@Composable
fun LynxApp(model: LynxModel) {
    MaterialTheme(colorScheme = lynxColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = LynxCanvas) {
            if (model.place == Place.Setup) {
                SetupScreen(model)
            } else {
                AppShell(model)
            }
            model.editor?.let { EditorHost(model, it) }
        }
    }
    LaunchedEffect(model.place) {
        while (isActive) {
            model.checkDue()
            delay(60_000)
        }
    }
}

@Composable
private fun AppShell(model: LynxModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 960.dp && model.place != Place.Settings
        Column(Modifier.fillMaxSize()) {
            TopBar(model)
            StatusLine(model)
            when {
                model.place == Place.Settings -> SettingsScreen(model, Modifier.weight(1f))
                wide -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    ContactList(model, Modifier.weight(0.38f).fillMaxHeight(), showAdd = true)
                    val personId = (model.place as? Place.Person)?.id
                    if (personId == null) {
                        Box(Modifier.weight(0.62f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text("Select a contact, or add one.", color = LynxTextMuted)
                        }
                    } else {
                        ContactScreen(model, personId, showBack = false, Modifier.weight(0.62f).fillMaxHeight())
                    }
                }
                model.place == Place.Home -> ContactList(model, Modifier.weight(1f), showAdd = true)
                model.place is Place.Person -> ContactScreen(
                    model,
                    (model.place as Place.Person).id,
                    showBack = true,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TopBar(model: LynxModel) {
    Row(
        Modifier.fillMaxWidth().background(LynxCanvas).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onPerson = model.place is Place.Person
        if (onPerson && model.place != Place.Settings) {
            TextButton(onClick = { model.openHome() }) { Text("Contacts", color = LynxBlue) }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when (model.place) {
                Place.Settings -> "Settings"
                is Place.Person -> model.contactName((model.place as Place.Person).id)
                else -> "Lynx"
            },
            color = LynxTextStrong,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        if (model.place != Place.Settings) {
            TextButton(onClick = { model.syncNow() }) { Text("Sync now", color = LynxBlue) }
            TextButton(onClick = { model.openSettings() }) { Text("Settings", color = LynxTextMuted) }
        } else {
            TextButton(onClick = { model.openHome() }) { Text("Done", color = LynxBlue) }
        }
    }
}

@Composable
private fun StatusLine(model: LynxModel) {
    val text = model.status ?: return
    Text(
        text,
        color = if (model.statusGood) LynxGreen else LynxRed,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SetupScreen(model: LynxModel) {
    var prefix by remember { mutableStateOf(model.prefix) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text("Lynx", color = LynxTextStrong, style = MaterialTheme.typography.headlineMedium)
        val device = if (model.phoneAccess == null) "computer" else "phone"
        Text(
            "Choose the folder where this $device keeps its Lynx file. Each device writes its own file there. Opening Lynx, leaving it, or Sync now pulls in everyone else's file. Two edits of the same note keep the edit that was saved later.",
            color = LynxText,
        )
        Text(model.folder?.let { folderLabel(it) } ?: "No folder chosen yet.", color = LynxTextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Choose folder") { model.chooseFolder() }
        }
        LynxField(prefix, { prefix = it }, "File prefix", "Phone or computer name")
        Text(lynxFileName(prefix), color = LynxBlue)
        model.blockedMessage?.let {
            Text(it, color = LynxRed)
            SecondaryButton("Start a new library in this folder") { model.startNewLibrary() }
        }
        model.phoneAccess?.let { access -> PermissionSetup(access) }
        PrimaryButton("Continue", enabled = model.folder != null) { model.usePrefix(prefix) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactList(model: LynxModel, modifier: Modifier, showAdd: Boolean) {
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            model.banner?.let { reminder ->
                Surface(color = LynxRaisedMore, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Due now", color = LynxOrange, fontWeight = FontWeight.Medium)
                        Text(model.contactName(reminder.contactId), color = LynxTextStrong)
                        Text(reminder.body, color = LynxText)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Done") { model.completeReminder(reminder.id) }
                            SecondaryButton("Dismiss") { model.dismissBanner() }
                            TextButton(onClick = { model.openPerson(reminder.contactId) }) {
                                Text("Open", color = LynxBlue)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(gap))
            }
            if (model.reminders.isNotEmpty()) {
                Text("Reminders", color = LynxTextStrong, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                model.reminders.forEach { reminder ->
                    ReminderRow(
                        name = model.contactName(reminder.contactId),
                        body = reminder.body,
                        due = formatWhen(reminder.dueAt),
                        overdue = model.isOverdue(reminder),
                        onOpen = { model.openPerson(reminder.contactId) },
                        onDone = { model.completeReminder(reminder.id) },
                        onEdit = { model.editor = Editor.ReminderEditor(reminder.contactId, reminder.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
            LynxField(model.search, { model.updateSearch(it) }, "Search", "Name, number, or note")
            if (model.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    model.tags.forEach { tag ->
                        val selected = tag.id in model.selectedTagIds
                        FilterChip(
                            selected = selected,
                            onClick = { model.toggleTag(tag.id) },
                            label = { Text(tag.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = tagChipFill(tag.name),
                                labelColor = tagColor(tag.name),
                                selectedContainerColor = LynxSelected,
                                selectedLabelColor = LynxBlue,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = LynxStroke,
                                selectedBorderColor = LynxBlue,
                            ),
                        )
                    }
                }
            }
            model.phoneAccess?.let { access ->
                if (!access.contactsGranted) {
                    Text(
                        "Contacts access is off. People you add here are still saved in Lynx.",
                        color = LynxTextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!access.notificationsGranted) {
                    Text("Reminder notifications are off.", color = LynxTextMuted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            model.skipped.forEach { skipped ->
                Text("${skipped.name}: ${skipped.reason}", color = LynxTextMuted, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            if (model.contacts.isEmpty()) {
                Text(
                    if (model.search.isBlank() && model.selectedTagIds.isEmpty()) {
                        "No contacts yet. Add someone to keep notes about them."
                    } else {
                        "No contacts match."
                    },
                    color = LynxTextMuted,
                )
            }
            model.contacts.forEach { contact ->
                ContactRow(model, contact) { model.openPerson(contact.id) }
            }
            Spacer(Modifier.height(72.dp))
        }
        if (showAdd) {
            FloatingActionButton(
                onClick = { model.editor = Editor.ContactEditor(null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = LynxButton,
                contentColor = LynxTextStrong,
            ) { Text("Add") }
        }
    }
}

@Composable
private fun ContactRow(model: LynxModel, contact: Contact, onClick: () -> Unit) {
    val selected = (model.place as? Place.Person)?.id == contact.id
    Column(
        Modifier.fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (selected) LynxSelected else LynxCanvas, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Text(contact.displayName, color = if (selected) LynxTextStrong else LynxText, fontWeight = FontWeight.Medium)
        contact.organization?.let { Text(it, color = LynxTextMuted) }
        val tags = model.personTags(contact.id).take(3)
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                tags.forEach { TagChip(it.name) }
            }
        }
        Text(model.notePreview(contact.id), color = LynxTextMuted, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TagChip(name: String) {
    Text(
        name,
        color = tagColor(name),
        modifier = Modifier.background(tagChipFill(name), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReminderRow(
    name: String,
    body: String,
    due: String,
    overdue: Boolean,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onEdit: () -> Unit,
) {
    Surface(color = LynxRaisedMore, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(12.dp)) {
            Text(name, color = LynxTextStrong)
            Text(body, color = LynxText)
            Text(due, color = if (overdue) LynxOrange else LynxTextMuted)
            Row {
                TextButton(onClick = onDone) { Text("Done", color = LynxGreen) }
                TextButton(onClick = onEdit) { Text("Edit", color = LynxBlue) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContactScreen(model: LynxModel, id: String, showBack: Boolean, modifier: Modifier) {
    val contact = model.person(id)
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showBack) {
            TextButton(onClick = { model.openHome() }) { Text("Back", color = LynxBlue) }
        }
        if (contact == null) {
            Text("This contact is no longer in the library.", color = LynxTextMuted)
            return
        }
        Text(contact.displayName, color = LynxTextStrong, style = MaterialTheme.typography.headlineSmall)
        Text(kindLabel(contact.kind), color = LynxTextMuted)
        contact.organization?.let { Text(it, color = LynxTextMuted) }
        contact.jobTitle?.let { Text(it, color = LynxTextMuted) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.personTags(id).forEach { tag ->
                FilterChip(
                    selected = false,
                    onClick = { model.removeTag(id, tag.id) },
                    label = { Text(tag.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = tagChipFill(tag.name),
                        labelColor = tagColor(tag.name),
                    ),
                )
            }
        }
        TagAdder(onAdd = { model.addTag(id, it) })
        Text("Numbers", color = LynxTextStrong)
        val phones = model.phones(id)
        if (phones.isEmpty()) Text("No numbers.", color = LynxTextMuted)
        phones.forEach { phone ->
            val clipboard = LocalClipboardManager.current
            val dial = model.dialNumber
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    phone.raw,
                    color = LynxBlue,
                    modifier = Modifier.weight(1f).then(
                        if (dial != null) Modifier.clickable { dial(phone.raw) } else Modifier,
                    ),
                )
                phone.label?.let { Text(it, color = LynxTextMuted) }
                if (dial == null) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(phone.raw)) }) {
                        Text("Copy", color = LynxBlue)
                    }
                }
            }
        }
        Text("Email", color = LynxTextStrong)
        val emails = model.emails(id)
        if (emails.isEmpty()) Text("No email addresses.", color = LynxTextMuted)
        emails.forEach { email ->
            Text(listOfNotNull(email.address, email.label).joinToString(" · "), color = LynxText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reminders", color = LynxTextStrong, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            PrimaryButton("Add reminder") { model.editor = Editor.ReminderEditor(id, null) }
        }
        val reminders = model.personReminders(id)
        if (reminders.isEmpty()) Text("No open reminders.", color = LynxTextMuted)
        reminders.forEach { reminder ->
            ReminderRow(
                name = contact.displayName,
                body = reminder.body,
                due = formatWhen(reminder.dueAt),
                overdue = model.isOverdue(reminder),
                onOpen = { model.editor = Editor.ReminderEditor(id, reminder.id) },
                onDone = { model.completeReminder(reminder.id) },
                onEdit = { model.editor = Editor.ReminderEditor(id, reminder.id) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notes", color = LynxTextStrong, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
            PrimaryButton("Add note") { model.editor = Editor.NoteEditor(id, null) }
        }
        val notes = model.notes(id)
        if (notes.isEmpty()) {
            Text("No notes yet. Add a note about a conversation.", color = LynxTextMuted)
        }
        notes.forEach { note ->
            Surface(
                color = LynxRaised,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { model.editor = Editor.NoteEditor(id, note.id) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(formatWhen(note.occurredAt), color = LynxTextMuted)
                    Text(note.body, color = LynxText)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Edit contact") { model.editor = Editor.ContactEditor(id) }
            TextButton(onClick = { model.editor = Editor.DeleteContact(id, contact.displayName) }) {
                Text("Delete", color = LynxRed)
            }
        }
    }
}

@Composable
private fun TagAdder(onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LynxField(name, { name = it }, "Tag", "Add a tag", Modifier.weight(1f))
        PrimaryButton("Add tag", enabled = name.isNotBlank()) {
            onAdd(name)
            name = ""
        }
    }
}

@Composable
private fun SettingsScreen(model: LynxModel, modifier: Modifier) {
    var prefix by remember(model.prefix) { mutableStateOf(model.prefix) }
    var region by remember(model.region) { mutableStateOf(model.region) }
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Text(
            "Each device has its own file. Opening Lynx, leaving it, or Sync now pulls in the other files. Two edits of the same note keep the edit that was saved later.",
            color = LynxText,
        )
        Text("Folder", color = LynxTextStrong)
        Text(model.folder?.let { folderLabel(it) } ?: "No folder chosen.", color = LynxTextMuted)
        SecondaryButton("Change folder") { model.chooseFolder() }
        LynxField(prefix, { prefix = it }, "Prefix", "File prefix")
        Text(lynxFileName(prefix), color = LynxBlue)
        PrimaryButton("Save prefix") { model.usePrefix(prefix) }
        Text("Other files", color = LynxTextStrong)
        if (model.otherFiles.isEmpty()) Text("No other Lynx files in this folder.", color = LynxTextMuted)
        model.otherFiles.forEach { Text(it, color = LynxTextMuted) }
        PrimaryButton("Sync now") { model.syncNow() }
        Text(
            "Last published " + (model.publishedAt?.toLongOrNull()?.let { formatWhen(it) } ?: "not yet"),
            color = LynxTextMuted,
        )
        Text("Tags", color = LynxTextStrong)
        if (model.tags.isEmpty()) Text("No tags yet. Add one from a contact.", color = LynxTextMuted)
        model.tags.forEach { tag -> TagEditor(tag.name, { model.renameTag(tag.id, it) }, { model.deleteTag(tag.id) }) }
        LynxField(region, { region = it }, "Phone region", "US")
        SecondaryButton("Save region") { model.updateRegion(region) }
        Text("Numbers are stored in international form when the region can read them.", color = LynxTextMuted)
        model.phoneAccess?.let { access ->
            Text("Phone access", color = LynxTextStrong)
            Text(
                if (access.contactsGranted) "Contacts access is on." else "Contacts access is off. Lynx still keeps people you type in.",
                color = LynxTextMuted,
            )
            if (!access.contactsGranted) SecondaryButton("Allow contacts") { access.requestContacts() }
            Text(
                if (access.notificationsGranted) "Reminder notifications are on." else "Reminder notifications are off.",
                color = LynxTextMuted,
            )
            if (!access.notificationsGranted) SecondaryButton("Allow notifications") { access.requestNotifications() }
        }
    }
}

@Composable
private fun PermissionSetup(access: PhoneAccess) {
    if (!access.contactsGranted && !access.contactsSkipped) {
        Text(
            "Lynx copies names, numbers, emails, company, and title into its file so your other devices can show the same people. Notes stay in Lynx.",
            color = LynxText,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Allow contacts") { access.requestContacts() }
            SecondaryButton("Skip") { access.contactsSkipped = true }
        }
    }
    if (!access.notificationsGranted && !access.notificationsSkipped) {
        Text("Due reminders can post a notification. Lynx does not schedule alarms.", color = LynxText)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Allow notifications") { access.requestNotifications() }
            SecondaryButton("Skip") { access.notificationsSkipped = true }
        }
    }
}

private fun folderLabel(folder: String): String {
    if (!folder.startsWith("content:")) return folder
    val decoded = folder.substringAfterLast('/')
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%20", " ")
    return decoded.substringAfterLast(':').ifBlank { "Selected folder" }
}

@Composable
private fun TagEditor(initial: String, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LynxField(name, { name = it }, "Tag name", initial, Modifier.weight(1f))
        SecondaryButton("Rename") { onRename(name) }
        TextButton(onClick = onDelete) { Text("Delete", color = LynxRed) }
    }
}

@Composable
private fun EditorHost(model: LynxModel, editor: Editor) {
    when (editor) {
        is Editor.ContactEditor -> ContactEditor(model, editor.id)
        is Editor.NoteEditor -> NoteEditor(model, editor.contactId, editor.noteId)
        is Editor.ReminderEditor -> ReminderEditor(model, editor.contactId, editor.reminderId)
        is Editor.DeleteContact -> ConfirmDialog(
            title = "Delete ${editor.name}?",
            body = "The contact leaves the list. Notes stay in the file and can come back if this contact is edited later on another device.",
            confirm = "Delete",
            onConfirm = { model.deleteContact(editor.id) },
            onCancel = { model.editor = null },
        )
        is Editor.PrefixClash -> PrefixClashDialog(model, editor.fileName, editor.suggestion)
    }
}

@Composable
private fun ContactEditor(model: LynxModel, id: String?) {
    val existing = id?.let { model.person(it) }
    var name by remember { mutableStateOf(existing?.displayName ?: "") }
    var organization by remember { mutableStateOf(existing?.organization ?: "") }
    var title by remember { mutableStateOf(existing?.jobTitle ?: "") }
    var kind by remember { mutableStateOf(existing?.kind ?: "unspecified") }
    val phones = remember {
        mutableStateOf(
            if (id == null) listOf(Line()) else model.phones(id).map { Line(it.raw, it.label ?: "") }.ifEmpty { listOf(Line()) },
        )
    }
    val emails = remember {
        mutableStateOf(
            if (id == null) listOf(Line()) else model.emails(id).map { Line(it.address, it.label ?: "") }.ifEmpty { listOf(Line()) },
        )
    }
    Dialog(onDismissRequest = { model.editor = null }) {
        Surface(color = LynxRaised, shape = RoundedCornerShape(12.dp)) {
            Column(
                Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (id == null) "New contact" else "Edit contact", color = LynxTextStrong, style = MaterialTheme.typography.titleLarge)
                LynxField(name, { name = it }, "Name", "Name")
                LynxField(organization, { organization = it }, "Organization", "Organization")
                LynxField(title, { title = it }, "Title", "Title")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("unspecified" to "Unmarked", "personal" to "Personal", "business" to "Business").forEach { (value, label) ->
                        FilterChip(
                            selected = kind == value,
                            onClick = { kind = value },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = LynxSelected,
                                selectedLabelColor = LynxBlue,
                                labelColor = LynxText,
                            ),
                        )
                    }
                }
                Text("Numbers", color = LynxTextStrong)
                phones.value.forEachIndexed { index, line ->
                    LineFields(line, "Number", "Label") { updated ->
                        phones.value = phones.value.toMutableList().also { it[index] = updated }
                    }
                }
                TextButton(onClick = { phones.value = phones.value + Line() }) { Text("Add number", color = LynxBlue) }
                Text("Email", color = LynxTextStrong)
                emails.value.forEachIndexed { index, line ->
                    LineFields(line, "Email", "Label") { updated ->
                        emails.value = emails.value.toMutableList().also { it[index] = updated }
                    }
                }
                TextButton(onClick = { emails.value = emails.value + Line() }) { Text("Add email", color = LynxBlue) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Save", enabled = name.isNotBlank()) {
                        model.saveContact(
                            id,
                            name,
                            organization,
                            title,
                            kind,
                            phones.value.filter { it.raw.isNotBlank() }.map { PhoneImport(it.raw, it.label.ifBlank { null }) },
                            emails.value.filter { it.raw.isNotBlank() }.map { EmailImport(it.raw, it.label.ifBlank { null }) },
                        )
                    }
                    SecondaryButton("Cancel") { model.editor = null }
                }
            }
        }
    }
}

private class Line(raw: String = "", label: String = "") {
    var raw by mutableStateOf(raw)
    var label by mutableStateOf(label)
}

@Composable
private fun LineFields(line: Line, rawHint: String, labelHint: String, onChange: (Line) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LynxField(line.raw, { line.raw = it; onChange(line) }, rawHint, rawHint, Modifier.weight(1.4f))
        LynxField(line.label, { line.label = it; onChange(line) }, labelHint, labelHint, Modifier.weight(0.8f))
    }
}

@Composable
private fun NoteEditor(model: LynxModel, contactId: String, noteId: String?) {
    val existing = noteId?.let { model.note(it) }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var whenText by remember { mutableStateOf(formatWhen(existing?.occurredAt ?: WallClock().epochMs())) }
    var reminder by remember { mutableStateOf("") }
    var dueText by remember { mutableStateOf(formatWhen(WallClock().epochMs())) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { model.editor = null }) {
        Surface(color = LynxRaised, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (noteId == null) "Add note" else "Edit note", color = LynxTextStrong, style = MaterialTheme.typography.titleLarge)
                Text(model.contactName(contactId), color = LynxTextMuted)
                LynxField(body, { body = it }, "Note", "What happened", singleLine = false)
                LynxField(whenText, { whenText = it }, "When", "yyyy-MM-dd HH:mm")
                LynxField(reminder, { reminder = it }, "Reminder", "Optional")
                if (reminder.isNotBlank()) LynxField(dueText, { dueText = it }, "Remind me", "yyyy-MM-dd HH:mm")
                error?.let { Text(it, color = LynxRed) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Save", enabled = body.isNotBlank()) {
                        val occurred = parseWhen(whenText)
                        val due = if (reminder.isBlank()) null else parseWhen(dueText)
                        if (occurred == null || (reminder.isNotBlank() && due == null)) {
                            error = "Use a date like 2026-11-05 14:30."
                        } else {
                            model.saveNote(contactId, noteId, body, occurred, reminder, due)
                        }
                    }
                    SecondaryButton("Cancel") { model.editor = null }
                }
            }
        }
    }
}

@Composable
private fun ReminderEditor(model: LynxModel, contactId: String, reminderId: String?) {
    val existing = reminderId?.let { model.reminder(it) }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var dueText by remember { mutableStateOf(formatWhen(existing?.dueAt ?: WallClock().epochMs())) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { model.editor = null }) {
        Surface(color = LynxRaised, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (reminderId == null) "Add reminder" else "Edit reminder", color = LynxTextStrong, style = MaterialTheme.typography.titleLarge)
                Text(model.contactName(contactId), color = LynxTextMuted)
                LynxField(body, { body = it }, "Reminder", "What to do")
                LynxField(dueText, { dueText = it }, "Due", "yyyy-MM-dd HH:mm")
                error?.let { Text(it, color = LynxRed) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Save", enabled = body.isNotBlank()) {
                        val due = parseWhen(dueText)
                        if (due == null) error = "Use a date like 2026-11-05 14:30."
                        else model.saveReminder(contactId, reminderId, body, due)
                    }
                    if (reminderId != null) {
                        TextButton(onClick = { model.deleteReminder(reminderId) }) { Text("Delete", color = LynxRed) }
                    }
                    SecondaryButton("Cancel") { model.editor = null }
                }
            }
        }
    }
}

@Composable
private fun PrefixClashDialog(model: LynxModel, fileName: String, suggestion: String) {
    var prefix by remember { mutableStateOf(suggestion) }
    Dialog(onDismissRequest = {}) {
        Surface(color = LynxRaised, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("That file name is taken", color = LynxTextStrong, style = MaterialTheme.typography.titleLarge)
                Text(
                    "$fileName belongs to another device. Lynx will not overwrite it. Choose a prefix this computer can publish.",
                    color = LynxText,
                )
                LynxField(prefix, { prefix = it }, "Prefix", suggestion)
                Text(lynxFileName(prefix), color = LynxBlue)
                PrimaryButton("Use this name") { model.usePrefix(prefix) }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel) {
        Surface(color = LynxRaised, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = LynxTextStrong, style = MaterialTheme.typography.titleLarge)
                Text(body, color = LynxText)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = LynxRed, contentColor = LynxTextStrong)) {
                        Text(confirm)
                    }
                    SecondaryButton("Cancel") { onCancel() }
                }
            }
        }
    }
}

@Composable
private fun LynxField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 4,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = LynxText,
            unfocusedTextColor = LynxText,
            focusedContainerColor = LynxField,
            unfocusedContainerColor = LynxField,
            cursorColor = LynxBlue,
            focusedBorderColor = LynxStroke,
            unfocusedBorderColor = LynxHairline,
            focusedLabelColor = LynxBlue,
            unfocusedLabelColor = LynxTextMuted,
            focusedPlaceholderColor = LynxPlaceholder,
            unfocusedPlaceholderColor = LynxPlaceholder,
        ),
    )
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LynxButton,
            contentColor = LynxTextStrong,
            disabledContainerColor = LynxButtonSecondary,
            disabledContentColor = LynxPlaceholder,
        ),
    ) { Text(text) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = LynxButtonSecondary, contentColor = LynxButtonSecondaryText),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(text) }
}

private fun kindLabel(kind: String) = when (kind) {
    "personal" -> "Personal"
    "business" -> "Business"
    else -> "Unmarked"
}
