# Lynx — Personal Relationship Management

Design for an implementing agent. Decisions in this document are locked. Build what it says. Do not reopen product choices, add a backend, or widen v1.

Lynx is a contacts app with a notebook attached to each person. There are two ways to write a note, and both are part of v1 on Android and desktop. Open a contact and add a note by hand, any time, about a call, a meeting, or anything else worth keeping. Or, on Android, call someone with the phone’s normal dialer and, when the call ends, Lynx asks whether you want a note. If you do, you write what mattered and it stays on that contact. If you don’t, Lynx forgets the call. The same notes open on a desktop app because every device keeps its own SQLite file in a folder you already sync (Google Drive, Dropbox, OneDrive, Syncthing, or a plain folder), and Lynx merges those files.

People use it for clients, colleagues, friends, and family. A contact can be marked personal, business, or left unmarked, and can carry any tags the user creates. A reminder sits on a contact, with an optional link to the note that prompted it.

## Build rules

- One repository, Kotlin Multiplatform, Compose Multiplatform, SQLDelight.
- Android and desktop share the database code, the matching code, and the screens.
- The phone’s own dialer places calls. Lynx is not a dialer, not an assistant handler, and does not declare `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS`, or `CALL_PHONE`.
- A dismissed prompt stores nothing. Lynx has no call history screen.
- Adding a note by hand is a primary action on every contact, on Android and desktop. It does not depend on call detection, notification access, or a prior prompt.
- Notes live only in the Lynx file. Do not write them into the Android contacts provider, and do not request `WRITE_CONTACTS`.
- No accounts, no server, no analytics, no crash reporter, no push token.
- Each device publishes its own `{prefix}_lynx.sqlite` in the shared folder. Sync merges those files row by row. Do not replace one whole database with another.
- Identifiers are UUID strings generated on the device. Do not use SQLite `AUTOINCREMENT` for rows that sync.
- Several Android phones can share one library. A person held on two phones becomes one contact, under the rules in Joining contacts.
- The app is dark. Every color comes from the Theme section. Do not use Material’s default purple scheme, and do not turn on Android dynamic color.
- Each phase ends in a runnable app and the tests listed for that phase. Do not start a later phase with the current phase’s tests failing.

## Stack

| Piece | Choice |
| --- | --- |
| Language | Kotlin, current stable 2.x |
| UI | Compose Multiplatform, Material 3, dark only, colors from the Theme section |
| Database | SQLDelight, dialect SQLite |
| Phone numbers | libphonenumber (`com.googlecode.libphonenumber`) |
| Time | `kotlinx-datetime` |
| Concurrency | `kotlinx-coroutines` |
| Android min / target | minSdk 29, targetSdk 36 |
| Desktop | One Compose for Desktop JVM module, `desktopApp`. v1 runs on Windows and on Linux. |
| Application id | `app.lynx.prm` until a real id is chosen |
| Dependency injection | None. A hand-built graph object wires the app. |

Target SDK 36 on purpose. Google Play’s contacts-permission enforcement for API 37 begins 27 January 2027. Staying on 36 keeps the first build off that deadline. The permission justification below is still required before anyone raises the target.

Desktop code uses `java.nio` paths and the Compose folder picker. The library preference is one path string in the app data directory: `%APPDATA%\Lynx\` on Windows, and `$XDG_CONFIG_HOME/lynx/` on Linux (`~/.config/lynx/` when `XDG_CONFIG_HOME` is unset). The device name published into the library file is the computer’s host name. macOS is out of scope for v1.

## Repository

```
settings.gradle.kts
gradle/libs.versions.toml
shared/
  src/commonMain/sqldelight/app/lynx/prm/db/
  src/commonMain/kotlin/app/lynx/prm/
  src/commonTest/kotlin/app/lynx/prm/
  src/androidMain/kotlin/app/lynx/prm/
  src/jvmMain/kotlin/app/lynx/prm/
androidApp/
desktopApp/
```

`shared` holds the schema, repositories, phone matching, contact matching, and the library-file copy. Platform source sets hold only the file-system and Android-provider edges. Screens live in `commonMain` and take repositories plus a small `Platform` interface (dial a number, open the library picker, report whether notification access is granted).

Suggested package split inside `app.lynx.prm`: `db`, `library`, `match`, `contacts`, `notes`, `tags`, `reminders`, `ui`.

## What the person does

1. On first launch, pick the shared folder and confirm the file prefix. The prefix starts as the phone name, or the computer name on desktop, sanitized. A phone named Bob publishes `bob_lynx.sqlite`. Lynx explains that each device writes its own file in that folder and that opening the app on another device combines them.
2. On Android, allow contacts access. Lynx copies names, numbers, emails, company, and title into the file so the desktop app can show the same people.
3. Open a contact and tap **Add note**. The composer already has that person selected. Write the note, set when it happened if it was not just now, and save. This works the same on Android and desktop, with or without notification access.
4. Call someone with the normal Phone app, or tap a number inside Lynx, which hands it to the Phone app with `ACTION_DIAL`.
5. When the call ends, a Lynx notification asks whether to add a note.
6. Add note opens a short composer with the person already chosen when the match is obvious. Save attaches the note to that contact. Not now clears the prompt and leaves no record.
7. Tag a contact, and add a reminder from the contact or from a note, with a due time. Open reminders show on the home screen. On Android, a due reminder also posts a notification.
8. Later, on the phone or the desktop, search the person and read every note, whether it was typed by hand or saved from a prompt. Tags, reminders, and notes from every device are there after a merge.

A second Android phone joins the same way: pick the same folder, give it its own prefix, and allow contacts access. A person held on both phones becomes one contact.

Desktop cannot see calls. A conversation logged there is a manual note: open the person, add a note, set the time if it was earlier. The phone has that same manual action. A missed prompt is not the only reason to use it.

## After a call

This is the only call feature.

Lynx learns that a call ended by watching notifications. The user turns on notification access once, from a Lynx screen that states the limit in one sentence: Lynx reads the caller name or number on the phone app’s call notification, and ignores every other notification.

### What counts as a call notification

Accept the notification only when all of these hold:

- The posting package is the current default dialer (`TelecomManager.defaultDialerPackage`) or one of: `com.google.android.dialer`, `com.android.dialer`, `com.samsung.android.dialer`, `com.samsung.android.incallui`.
- And either the category is `call` or `missed_call`, or the notification is an ongoing call-style notification from one of those packages.

Anything else is dropped on that check. Do not log it, do not keep the title, do not inspect message bodies.

### When to ask

Remember an accepted notification while it is posted. Remember its key, the time it was posted, its category, and the visible title and text.

Ask when that notification is removed, and it had been posted for at least two seconds. Removal sooner than that is noise.

Also ask when a new `missed_call` notification is posted, because a missed call is not always preceded by a notification Lynx saw removed.

Ask once per call. If a prompt for the same title was shown in the last 60 seconds, do not ask again. That covers the case where the ringing notification goes away and a missed-call notification follows.

The question is a high-priority notification, not an activity that takes over the screen. Posting it needs `POST_NOTIFICATIONS`, like every Lynx notification.

| Situation | Notification text |
| --- | --- |
| Title matches one contact | Add a note about your call with Dana Shah? |
| Title is a phone number | Add a note about your call with +1 555-0100? |
| Title is generic or empty | Add a note about the call that just ended? |
| Missed, one contact or a number | Add a note about a missed call from Dana Shah? |

Generic titles, treated as no identity: `Ongoing call`, `On-going call`, `Current call`, `Phone`, `Call`, `Call in progress`, `Mobile`, `Dialing`, and the same strings with different letter case.

Actions: **Add note** and **Not now**. The notification goes away on its own after 30 minutes. Both the timeout and Not now discard the captured title. Nothing is written to SQLite.

**Add note** opens the composer. The captured title is matched with the rules in Phone numbers and Matching a caller. The composer shows:

- One clear match: that contact, with a way to change it.
- Several matches: those contacts, and a search box.
- No match: the captured name or number, a search of Lynx contacts, and **Create contact**. When the captured title is a phone number and the user chooses a contact from the search, **This is the same person** saves that number on the chosen contact, so the next call from it matches.

Saving with an empty body is refused. Saving writes one note, plus that contact if the user created one, plus one `phone` row if the user chose **This is the same person**. `source` is `call_prompt`. `occurred_at` is the time the call notification was first seen, or “now” for a missed-call notification. Direction is `missed` for a missed-call notification and `unknown` otherwise. Do not infer incoming versus outgoing from notification text.

### Service shape

`CallNotificationListener` extends `NotificationListenerService` and lives in `androidApp`. It is exported and requires `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`. It does no database work itself. It hands a `CapturedCall` (`title`, `text`, `category`, `postedAtEpochMs`, `endedAtEpochMs`) to a `CallPromptController` in `shared`, which decides whether to notify and which contact ids to preselect.

The controller is pure enough to unit test: given a sequence of posted and removed notifications and a contact list, assert whether a prompt fires and which contact ids are attached.

## Contacts

Android contacts are a source of identity. Lynx is the source of notes.

On first grant, and again each time the Android app becomes active, import:

- Display name
- `LOOKUP_KEY` (the stable id; do not key off `CONTACT_ID`)
- Organization and job title
- Every phone number and its label
- Every email and its label

Skip a contact that has no name and no phone number.

Upsert by `android_lookup_key`. Look the key up across every contact row, including soft-deleted ones. If the row found has `merged_into` set, follow it to the end and use that contact, so a phone whose key sits on a joined contact updates the survivor and does not recreate the person.

Each Android install keeps a private import record: for each lookup key, the name, organization, title, numbers, and emails it saw at its last import. The record lives in app storage outside `work.sqlite`, so it is never published. Import writes only what differs from that record, so two phones that spell a person differently do not overwrite each other on every open. A phone with no record for a key is seeing that person for the first time, so it fills in what the contact is missing and changes nothing else.

When the key matches, write a name, organization, or title that changed on this phone, and set `updated_at` and `updated_by`. Do not touch notes, tags, or reminders. Match phones and emails already on that contact by normalized number or by email address: update a changed label in place, insert a missing one, and soft-delete one that is in this phone’s import record and that the phone no longer has. Do not remove a number or email this phone never imported, because another phone may have added it. Do not delete and recreate those rows. New ids on every import would survive merge as duplicates. A Lynx contact with no lookup key was created inside Lynx; import must not delete it, and it never joins another contact automatically. Only **This is the same person** on the Contact screen joins it to another contact.

If a phone contact disappears, leave the Lynx contact and its notes as they are.

The user can also create a contact by hand on either platform: name (required), organization, title, kind (`personal`, `business`, `unspecified`), phones, emails, and tags.

`READ_CONTACTS` is required for the product, not as a convenience. After a call the app has to recognize the person without a picker step, and the contact list itself is the app. The Android contact picker is one-shot and cannot feed the desktop file. Before targetSdk 37, file the Play Console Contacts declaration:

- Use case: Contact management, and CRM (notes on contacts).
- Why the picker is not enough: Lynx keeps a full relationship list, refreshes it into a database the user syncs to other devices, and must match the person from a just-ended call without asking them to pick a contact every time.

If the user denies contacts access, the app still runs. Contacts are ones they type in, and the after-call composer always asks who the call was with.

Tapping a number on Android sends `Intent.ACTION_DIAL` with a `tel:` URI. Lynx does not place the call.

## Notes

A note belongs to one contact. The user can always add one from the contact screen. Call prompts are a shortcut to the same composer, with the contact and time filled in.

### Manual note

From a contact, **Add note** opens the composer with that contact fixed. The user types the body and may change when the conversation happened. Save writes one row with `source = manual`, `call_direction` null, and both call-number fields null. Cancel writes nothing.

The action is on the contact screen on both platforms, including a contact that already has notes and a contact that has none. It stays available when notification access is off, when contacts permission is denied, and when the contact was typed in by hand rather than imported.

| Field | Meaning |
| --- | --- |
| body | What the user wanted to remember. Required. Plain text. |
| source | `call_prompt` or `manual` |
| occurred_at | When the conversation happened. Defaults to now. Editable so a missed prompt can be filled in later. |
| call_direction | `missed`, `unknown`, or null for a manual note |
| call_number_raw / call_number_normalized | The number from the notification when there was one. Null otherwise. |

Editing a note updates `body`, `occurred_at`, `updated_at`, and `updated_by`. Deleting a note sets `deleted_at` and bumps `updated_at` and `updated_by`. Lists hide deleted rows. There is no undo screen in v1. The row stays so another device can learn about the delete through merge.

Contact list order: most recent non-deleted note first, then display name. People with no notes sort by name after people with notes.

Search matches display name, organization, phone raw value, email, tag name, reminder body, and note body. Use SQL `LIKE` with the user’s text wrapped in `%`. Enough for v1.

## Tags

Tags are labels the user types, shared across contacts. “Client”, “family”, and “holiday card” are tags. Kind stays a separate field.

A tag has one name. The normalized name is the name trimmed and lowercased. Two tags with the same normalized name are the same tag; merge collapses them.

On a contact, the user adds a tag by typing. Choosing an existing name links that tag. Typing a new name creates the tag and links it. Removing a chip unlinks it from that contact and leaves the tag in place for other people. Settings lists every tag and can rename or delete it. Deleting a tag soft-deletes the tag and every link to it.

Home can filter the contact list by tag. With several tags selected, a contact must have all of them. The filter row is hidden when the user has no tags.

## Reminders

A reminder belongs to one contact. It may also point at the note that prompted it. The body says what to do (“Send the revised quote”). `due_at` is the time to surface it. `done_at` is set when the user marks it done.

Add one from the contact screen, or from the note composer with an optional “Remind me” date and time. A note save with that option writes the note and one reminder linked to it. Clearing the option writes the note only.

The home screen lists open reminders above the contacts: overdue first, then soonest due. Each row shows the contact name, the body, and the due time. Tapping the row opens the contact. **Done** sets `done_at`. The user can change the body and the due time until then. Deleting a reminder soft-deletes it.

Android posts a notification when a reminder comes due, using WorkManager, and reschedules every open reminder on startup and after a merge. The notification title is the contact name and the text is the reminder body. Tapping it opens the contact. This uses the same `POST_NOTIFICATIONS` permission as the call prompt and does not depend on notification access. Do not schedule exact alarms.

Desktop has no background notifier. The home list is the reminder. While the window is open, check once a minute and show an in-app banner for anything newly due.

## The library folder

The user picks a directory. Each device publishes one file there, named `{prefix}_lynx.sqlite`. A phone named Bob publishes `bob_lynx.sqlite`. The cloud client syncs every device’s file. Lynx never puts `-wal` or `-shm` files in that directory.

The prefix is chosen on setup and can be changed in settings. It is filled in with the device name: on Android, `Settings.Global.DEVICE_NAME`, then `Build.MODEL` if that is blank; on desktop, the computer’s host name.

Sanitize before using it as a file name: lowercase; drop every character that is not `a`–`z`, a digit, or whitespace; turn each run of whitespace into a single `_`; trim `_`; cut to 40 characters. If nothing is left, the prefix is `lynx` and the file is `lynx_lynx.sqlite`. Show the resulting file name next to the field while the user edits it. `Bob's Pixel` becomes `bobs_pixel_lynx.sqlite`.

Each device also keeps a private working copy, `work.sqlite`, in app storage. SQLDelight opens the working copy only. The shared folder is not the live database. Cloud clients sync closed files and never see SQLite’s sidecar files.

Local preferences, outside the database, remember:

- The folder.
- The prefix.
- `device_id`, a UUID created on first launch and never changed for that install.
- The phone region.
- `library_id`, once known.

Desktop stores them in the app data directory named above (`%APPDATA%\Lynx\` on Windows, `$XDG_CONFIG_HOME/lynx/` or `~/.config/lynx/` on Linux). Android stores the folder as a persistable tree URI from `ACTION_OPEN_DOCUMENT_TREE` and writes files through the Storage Access Framework. The working copy is still a real file path, because SQLite needs one.

### Which files merge

On open, on **Sync now**, and before publish, merge every file in the folder whose name matches `{something}_lynx.sqlite`.

Skip, with a visible reason, a file that:

- Is empty, does not start with `SQLite format 3`, or is a `*.tmp` file. A zero-byte file is the usual cloud placeholder. Do not overwrite it.
- Has `schema_version` newer than this app. Tell the user to update Lynx.
- Has a `library_id` different from this device’s. List it as a different library.

If this device has no `library_id` yet, adopt one from the peers. When the peers disagree, adopt the lexicographically smallest `library_id` and skip the files that carry another. The first device to create a file generates the `library_id`.

If this install already has a `library_id` and every candidate file is missing or a placeholder, stop. Tell the user the files are not on this device yet. Do not create a second library.

### Opening

1. No folder chosen: show setup. Do not create a database in a default cloud folder.
2. Open or create the working copy.
3. Merge every eligible peer file into the working copy, including this device’s own published file, using the rules in Merge.
4. If the merged working copy differs from this device’s published file, publish.

Switching folders in settings closes the working copy and does not publish it into the newly chosen folder. It then opens that folder with the rules above.

### Publishing

Publish when the Android app goes to the background (`ProcessLifecycleOwner` stopped), when the desktop window closes, and when the user taps **Sync now**. Merge peers first, then write.

1. Commit, checkpoint, and close the working connection so the file is one complete database.
2. Write `{prefix}_lynx.sqlite.tmp` in the library folder, flush it, and check the SQLite header and a non-zero size.
3. Replace `{prefix}_lynx.sqlite` with the temp file. Reopen the working connection if the process is still alive.

On Android providers that cannot rename, write the temp document, verify it, delete this device’s published file, then rename the temp into place. Never delete the published file before the temp file is verified. Never delete another device’s file.

If `{prefix}_lynx.sqlite` already exists and its `device_id` is present and is not this install’s id, do not overwrite it. Settings stays on the prefix field and explains that the name belongs to another device. Suggest the current prefix plus the first four hex characters of this `device_id` (`bob_a1b2_lynx.sqlite`).

Changing the prefix publishes the new name. If the previous file’s `device_id` is this install’s, delete that previous file after the new one is verified. Merge by id makes a leftover copy harmless, but leaving it in the folder would keep showing this device twice.

### SQLite settings on the working copy

- `journal_mode=WAL` is fine on the working copy, because that file never sits in the sync folder.
- `foreign_keys=ON`
- `synchronous=FULL`

### What to tell the user

On setup and in settings: each device has its own file in the shared folder; opening Lynx, leaving it, or tapping Sync now pulls in everyone else’s file; two edits to the same note keep the edit that was saved later; a person held on two phones becomes one contact.

## Merge

Every published file is a full copy of the library, not a changelog. Merge unions them by primary key.

Mutable tables, in this order: `tag`, `contact`, `phone`, `email`, `note`, `contact_tag`, `reminder`.

For one id:

- Missing locally: insert the incoming row.
- Present locally: keep the row with the greater `updated_at`. If the timestamps are equal, keep the row whose `updated_by` is lexicographically greater.

Copy the whole winning row. Do not mix columns from both versions. One exception: when only one copy of a contact has `merged_into` set, keep that copy, so an older device’s edit never undoes a join.

A delete sets `deleted_at` and sets `updated_at` to that moment. A later delete beats an earlier edit. A later edit beats an earlier delete and the row is visible again.

Times are the device’s wall clock. v1 does not try to correct a clock that is set ahead.

After the row merge, join contacts under the rules in Joining contacts, then collapse tags. Group non-deleted tags by normalized name. The survivor is the one with the earliest `created_at`, then the smallest id. For each loser, point its non-deleted `contact_tag` rows at the survivor. If that contact already has a non-deleted link to the survivor, soft-delete the extra link. Soft-delete the loser tag. Those writes use the current time and this device’s `device_id`, so the collapse wins against the old loser rows on the next merge.

Rows the other device has never seen stay as they are. A note added on the phone and a different note added on the desktop both remain. A name edited on the desktop and a tag added on the phone both remain, because they are different rows. Two edits to the same note keep the newer save only. State that limit in settings in one sentence. Do not build a side-by-side editor for that case.

### Joining contacts

Each phone imports people under its own new ids, so one person can arrive from two phones as two rows. Joining turns them into one contact. It runs after every row merge, and the same files give the same result on every device.

Two non-deleted contacts that both have an `android_lookup_key` join automatically when either of these holds:

- They have the same `android_lookup_key`.
- They have the same name, compared as in Matching a caller, and they share a phone number under the rule in Phone numbers, or an email address compared trimmed and case-insensitive.

If A joins B and B joins C, all three join. Do not join on a shared number or email alone, because families and offices share them. A contact with no lookup key never joins automatically.

Everything else is joined by hand with **This is the same person** on the Contact screen. It uses the same steps and is confirmed first, because a join has no undo in v1.

To join a group:

1. The survivor is the contact with the earliest `created_at`, then the smallest id. It keeps its own name, kind, organization, title, and lookup key.
2. Point the other contacts’ non-deleted `phone`, `email`, `note`, `contact_tag`, and `reminder` rows at the survivor.
3. Where the survivor now has the same number, email, or tag twice, keep the row with the earliest `created_at`, then the smallest id, and soft-delete the rest.
4. Soft-delete each other contact and set its `merged_into` to the survivor.

Those writes use the current time and this device’s `device_id`, so the join wins against the old rows on the next merge.

On every merge, also move any non-deleted row that still points at a contact with `merged_into` set to the end of that contact’s `merged_into` chain. A note added on another device before it saw the join lands on the survivor and is not lost.

## Database

SQLDelight schema. Times are epoch milliseconds. Primary keys are UUID text.

```sql
CREATE TABLE meta (
  key TEXT NOT NULL PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE contact (
  id TEXT NOT NULL PRIMARY KEY,
  display_name TEXT NOT NULL,
  organization TEXT,
  job_title TEXT,
  kind TEXT NOT NULL DEFAULT 'unspecified',
  android_lookup_key TEXT,
  merged_into TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE phone (
  id TEXT NOT NULL PRIMARY KEY,
  contact_id TEXT NOT NULL REFERENCES contact(id),
  raw TEXT NOT NULL,
  normalized TEXT NOT NULL,
  label TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE email (
  id TEXT NOT NULL PRIMARY KEY,
  contact_id TEXT NOT NULL REFERENCES contact(id),
  address TEXT NOT NULL,
  label TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE note (
  id TEXT NOT NULL PRIMARY KEY,
  contact_id TEXT NOT NULL REFERENCES contact(id),
  body TEXT NOT NULL,
  source TEXT NOT NULL,
  call_direction TEXT,
  call_number_raw TEXT,
  call_number_normalized TEXT,
  occurred_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE tag (
  id TEXT NOT NULL PRIMARY KEY,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE contact_tag (
  id TEXT NOT NULL PRIMARY KEY,
  contact_id TEXT NOT NULL REFERENCES contact(id),
  tag_id TEXT NOT NULL REFERENCES tag(id),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE TABLE reminder (
  id TEXT NOT NULL PRIMARY KEY,
  contact_id TEXT NOT NULL REFERENCES contact(id),
  note_id TEXT REFERENCES note(id),
  body TEXT NOT NULL,
  due_at INTEGER NOT NULL,
  done_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  deleted_at INTEGER
);

CREATE INDEX phone_normalized ON phone(normalized);
CREATE INDEX phone_contact ON phone(contact_id);
CREATE INDEX email_contact ON email(contact_id);
CREATE INDEX note_contact_occurred ON note(contact_id, occurred_at);
CREATE INDEX contact_tag_contact ON contact_tag(contact_id);
CREATE INDEX contact_tag_tag ON contact_tag(tag_id);
CREATE INDEX reminder_open_due ON reminder(done_at, due_at);
CREATE INDEX contact_lookup ON contact(android_lookup_key);
```

`kind` is `personal`, `business`, or `unspecified`. `source` is `call_prompt` or `manual`. `call_direction` is `missed`, `unknown`, or null. `updated_by` is the `device_id` of the install that last wrote the row. `note_id` on a reminder is null when the reminder was added from the contact rather than from a note.

Meta keys written by the app: `schema_version`, `library_id` (set once when the library is created), `device_id`, `device_name`, `file_prefix`, `published_at`. `device_name` is the Android device name or the desktop computer name, refreshed on each publish. `file_prefix` is the sanitized prefix this file was published under.

`schema_version` starts at `1`. Ship schema changes as SQLDelight migrations. Do not edit a shipped migration.

The index on `android_lookup_key` is not unique, because two phones can import one person under different ids with the same key, and merge has to accept both rows before they join. Hand-created contacts leave the key null.

`merged_into` is null until a contact is joined into another. It has no foreign key, because merge can insert a joined contact before its survivor.

## Phone numbers

Default region comes from the device locale. Settings has a region override stored in local preferences, not in the library file, so a traveler does not rewrite the shared file just by changing a preference.

For each raw number:

1. Parse with libphonenumber and the default region.
2. When parsing succeeds, `normalized` is E.164 (`+15555550100`).
3. When it fails, `normalized` is the digits only, with a leading `+` kept if the raw string had one.

Two numbers match when their `normalized` values are equal, or when both have at least 10 digits and the last 10 digits are equal.

A captured title is a phone number when, after stripping spaces, dashes, parentheses, and dots, it is optional `+` plus at least 7 digits. Otherwise it is a name.

## Matching a caller

Input is the notification title and the current Lynx contacts.

1. If the title is generic or blank, there is no match.
2. If the title is a phone number, match it against every `phone.normalized` using the rule above. Several contacts can match.
3. If the title is a name, compare it to `display_name` with trimmed, case-insensitive equality. Do not fuzzy-match in v1. Several contacts can share a name.

Zero matches, one match, or many matches are all normal outcomes. The composer handles each, as described in After a call.

## Theme

One Dark Pro Night Flat, the VS Code theme in `OneDark-Pro-night-flat.json` from Binaryify/OneDark-Pro, is the source of every color. Night Flat is the flat variant: one near-black canvas, almost no dividing lines, and the One Dark syntax colors used in small doses.

Put these in one `ColorScheme` in `commonMain` and pass it to `MaterialTheme` on Android and desktop. Turn dynamic color off. The desktop window background is the same canvas color, so the frame around the Compose view is not white or gray.

| Role | Hex | Use |
| --- | --- | --- |
| Canvas | `#16191d` | Window, screens, lists, navigation. Material `background` and `surface`. |
| Raised | `#1e2227` | Dialogs, menus, the note composer sheet, settings groups. Material `surfaceContainer`. |
| Raised more | `#23272e` | Active tab, reminder card, search field when it sits on the canvas. Material `surfaceContainerHigh`. |
| Hover and selected row | `#2c313a` | Contact row under the pointer or selected. List selection in the theme. |
| Field | `#1d1f23` | Text fields. |
| Hairline | `#181a1f` | The rare divider. Prefer space over a line. |
| Stroke | `#3e4452` | Focus ring, field border when focused. The theme’s `focusBorder`. |
| Text | `#abb2bf` | Names, note body, field text. |
| Text strong | `#d7dae0` | Screen titles, the selected row’s name, button labels. |
| Text muted | `#9da5b4` | Organization, timestamps, secondary lines. |
| Placeholder | `#6b717d` | Hints, disabled labels, the inactive title-bar gray. |
| Button | `#404754` | Filled buttons, including Save and Add note. Label is Text strong. |
| Button secondary | `#30333d` | Cancel and Not now. Label `#c0bdbd`. |
| Blue | `#61afef` | Links, the focused prefix, a selected tag filter, tappable phone numbers. |
| Green | `#98c379` | A reminder marked done, a successful sync line. |
| Purple | `#c678dd` | One of the tag chip colors. |
| Yellow | `#e5c07b` | One of the tag chip colors. |
| Orange | `#d19a66` | Overdue reminder time, and one tag chip color. |
| Cyan | `#56b6c2` | One of the tag chip colors. |
| Red | `#e06c75` | Delete, destructive confirmation, error text. |
| Error strong | `#c24038` | The error icon only. Body text of an error stays Red. |
| Badge | `#4d78cc` | A count badge, if one exists. Text on it is `#f8fafd`. |

Tag chips use Blue, Green, Purple, Yellow, Orange, and Cyan, chosen by a stable hash of the normalized tag name so a tag keeps its color on every device. The chip fill is that color mixed at about 20% over the canvas. The chip label is the full color. Red is not a tag color.

Do not color the personal / business / unmarked kind. Tags are the colored labels.

Empty states, the after-call notification, and the Android reminder notification use the same text and canvas colors. The notification can only set a title, text, and actions; it does not embed the Compose theme. That limitation is fine. In-app screens follow the table.

## Screens

One navigation structure on both platforms. Compact width is a single column with a back stack. Expanded width (a desktop window, or a wide phone in landscape is optional) shows the contact list beside the open contact.

**Setup.** Short explanation of the shared folder, a button to choose it, and the prefix field with the device name filled in and the file name shown beside it (`bob_lynx.sqlite`). Android adds the contacts permission, the notifications permission (`POST_NOTIFICATIONS`), and the notification-access step here, each with a skip. Skipping contacts is allowed. Skipping notification access turns off call detection only. Skipping the notifications permission turns off every Lynx notification. Either one shows a note on the home screen.

**Home.** Open reminders, then a search field, then tag filter chips when any tags exist, then the contacts. Contact rows: name, organization if any, up to three tag chips, and the first line of the latest note or “No notes yet”. A floating or toolbar action creates a contact.

**Contact.** Name, kind, organization, title, tags. Numbers and emails. Number rows on Android are tappable and dial. Open reminders for this person, with **Add reminder**. Note list, newest first, each showing time and body. **Add note** is a visible primary action on this screen, on Android and desktop. Edit contact. **This is the same person** searches for another contact and, after a confirmation, joins the two under the rules in Joining contacts. Delete contact sets `deleted_at` and bumps `updated_at` and `updated_by`, and is confirmed first. Notes, tags links, and reminders stay in the file on that contact id and disappear from lists because the contact is hidden.

**Note composer.** Body, date and time of the conversation, optional reminder body and due time, Save, Cancel. Opened from a contact, that contact is already selected and is not asked for again. Opened from a call prompt, the contact chooser described in After a call sits at the top. Save is enabled only when a contact is chosen and the note body is non-blank. A reminder whose body is blank is not written.

**Reminder editor.** Body and due date and time. Used from the contact screen and from the home row’s edit. **Done** is on the home row and on the contact’s reminder row.

**Settings.** Current folder, prefix and the resulting file name, the other Lynx files found in the folder, Change folder, Sync now, last published time, tag list with rename and delete, phone region, and on Android three permission rows with their current state: contacts, notifications, and notification access.

**Prefix clash.** The block described under Publishing. The user cannot leave setup, or cannot save the new prefix, until the file name is free for this device.

Empty library, empty contact list, and a contact with no notes each have one sentence of explanation and the action that fills them.

## Android permissions

Declare only:

- `READ_CONTACTS`
- `POST_NOTIFICATIONS` (request at runtime on API 33+)
- The notification listener service binding permission on the service, not as a normal dangerous permission

Request contacts and notifications in context, on the setup screen, with the reason visible before the system dialog. Notification access is a settings intent (`ACTION_NOTIFICATION_LISTENER_SETTINGS`), not a runtime permission dialog. Notification access controls call detection only. `POST_NOTIFICATIONS` controls every Lynx notification.

Do not declare a dialer role, an in-call service, or `ACTION_DIAL` as an activity filter that would make Lynx the default phone app. `ACTION_DIAL` is an intent Lynx sends, not one it handles.

Play listing, when there is one, needs a privacy policy that says: contacts and notes stay in the user’s SQLite file, call prompts read only the phone app’s call notification title, declined prompts are not stored, and there is no Lynx server. Publishing to Play is out of scope for the build.

## Phases

### Phase 1 — Shared core, no UI

Schema, repositories for contacts, notes, tags, and reminders, phone normalization, caller matching, prefix sanitizing, and merge, including joining contacts, against temp directories and a fake clock.

Tests, JVM, `shared/src/commonTest`:

- E.164 and last-10 matching, including a failed parse.
- Name match is case-insensitive and does not match a substring.
- Generic titles do not match.
- Prompt controller: removal under two seconds does not ask; a missed-call notification within 60 seconds of a prompt for the same title does not ask again; Not now writes no note.
- `Bob` sanitizes to `bob` and the file name `bob_lynx.sqlite`. `Bob's Pixel` sanitizes to `bobs_pixel`. A blank name sanitizes to `lynx`.
- Open on an empty folder creates `{prefix}_lynx.sqlite`.
- Open skips a zero-byte peer and does not overwrite it.
- A note that exists only in `bob_lynx.sqlite` and a note that exists only in `office_lynx.sqlite` are both present after merge.
- The same note id keeps the body with the later `updated_at`. Equal timestamps keep the greater `updated_by`.
- A later delete hides a row. A later edit makes a deleted row visible again.
- Two tags with different ids and the same normalized name collapse to one, and contact links follow the survivor.
- Two imported contacts with different ids and the same `android_lookup_key` merge without error into one contact, with every note kept.
- Two imported contacts with the same name and a shared number join. With different names and a shared number, they stay two contacts.
- A contact with no lookup key never joins automatically. **This is the same person** joins it, and its notes, tags, and reminders move to the survivor.
- Merging the same files in either order picks the same survivor.
- A note added to a joined contact on a device that had not seen the join ends up on the survivor.
- An older device’s edit to a joined contact does not bring it back.
- A reminder marked done on one file stays done after merge with an older open copy.
- Publish is refused when the prefixed file’s `device_id` belongs to another install, and that file’s bytes are unchanged.

### Phase 2 — Desktop

Runnable with `:desktopApp:run` on Windows and on Linux. The window uses the Night Flat colors from Theme, with dynamic color off. Setup folder picker and prefix, home with reminders and tag filters, contact, composer, reminder editor, settings, sync now. Creating a contact, tagging it, adding a note, adding a reminder, restarting the app, and seeing all three is the demo on each OS. Joining two contacts with **This is the same person** and seeing one contact with both sets of notes is part of that demo. Point two installs at one folder, give them different prefixes, add a different note on each, sync, and see both notes on both. Pointing the folder at a real cloud directory is a manual check, not a unit test. A file written on one OS merges on the other.

### Phase 3 — Android contacts

Same screens on a device or emulator. The prefix field opens filled with the phone’s name. Contacts import and `ACTION_DIAL`. From an imported contact, Add note, a tag, and a reminder survive a restart. A due reminder posts a notification. Deny-permission path still creates a hand-entered contact and a note on it. Import test can be a JVM test of the mapper: a fake provider row becomes a contact, a second import updates the name without dropping a note, a second import of the same number keeps the same phone row id, an import whose key sits on a joined contact updates the survivor and creates no contact, an import does not remove a number another phone added, and two phones that spell a name differently do not overwrite each other on repeated imports. The on-device check is the import, the manual note, the tag, and the reminder notification. Two devices or emulators that hold the same contact and share a folder show that person as one contact after a sync.

### Phase 4 — After-call prompt

The listener, the Lynx notification, and the composer entry, including **This is the same person** for an unmatched number. Unit tests from phase 1 still cover the decision. On a device, place a call with the stock dialer, hang up, and confirm the notification. Tap Add note and save. Tap Not now on a second call and confirm no new note exists. This phase is done only after that device check.

### Phase 5 — Pass over the empty states, the prefix and merge explanation, and the setup copy

No new features in this phase.

## Out of scope

Photo avatars, multiple note attachments, voice transcription, SMS, email sync, writing notes back to Google Contacts or the phone address book, accounts, any phone-app replacement, and a macOS build.

## Done

v1 is done when a person can:

- Choose a folder and keep the default prefix so the phone’s file is named like `bob_lynx.sqlite`. Change the prefix and see the file name change. Open that folder in the desktop app on Windows and on Linux.
- See phone contacts on both, after an Android import and a publish.
- Open a contact on the phone and on the desktop and add a note by hand, with no call involved. Tag the contact on one device and add a reminder on the other. After a sync, both devices show the note, the tag, and the reminder.
- Call from the stock dialer, get one “add a note?” notification, save a note on the matched contact, and read that note on the desktop after the phone app has published and the desktop has merged.
- Dismiss a second prompt and find no trace of that call in the database.
- On Android, have a due reminder post a notification.
- Edit the same note on both devices and, after the merge, see the edit that was saved later. Add a different note on each device and see both notes survive.
- Use a second Android phone on the same library and see a person held on both phones as one contact.
