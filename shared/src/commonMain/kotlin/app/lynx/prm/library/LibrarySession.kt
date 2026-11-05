package app.lynx.prm.library

import app.cash.sqldelight.db.SqlDriver
import app.lynx.prm.db.Clock
import app.lynx.prm.db.Ids
import app.lynx.prm.db.LynxDatabase
import app.lynx.prm.db.epochMs
import app.lynx.prm.match.lynxFileName
import app.lynx.prm.match.sanitizePrefix
import app.lynx.prm.match.suggestedPrefix
import app.lynx.prm.store.LynxStore

const val SCHEMA_VERSION: Long = 1

data class SkippedFile(val name: String, val reason: String)

sealed class OpenResult {
    data class Ready(val libraryId: String, val skipped: List<SkippedFile>) : OpenResult()
    data class FilesNotOnDevice(val message: String) : OpenResult()
    data class PrefixInUse(val fileName: String, val suggestedPrefix: String) : OpenResult()
}

class LibrarySession(
    private val libraryFolder: String,
    private val deviceId: String,
    private val deviceName: String,
    private val requestedPrefix: String,
    private val knownLibraryId: String?,
    private val defaultRegion: String,
    private val clock: Clock,
    private val ids: Ids,
    private val files: PlatformFiles = platformFiles(),
    private val workFolder: String = files.tempDirectory(),
) {
    val prefix: String = sanitizePrefix(requestedPrefix)
    val fileName: String = lynxFileName(prefix)
    var libraryId: String? = knownLibraryId
        private set

    private var driver: SqlDriver? = null
    private var database: LynxDatabase? = null

    fun store(): LynxStore {
        val db = database ?: error("Open the library before using it.")
        return LynxStore(db, clock, ids, deviceId, defaultRegion)
    }

    fun open(): OpenResult {
        val inspected = inspectFolder()
        val valid = inspected.filterIsInstance<ValidFile>()
        if (knownLibraryId != null && valid.isEmpty()) {
            return OpenResult.FilesNotOnDevice("The library files are not on this device yet.")
        }
        val chosen = knownLibraryId
            ?: valid.mapNotNull { it.libraryId }.minOrNull()
            ?: ids.newId()
        libraryId = chosen

        val skipped = mutableListOf<SkippedFile>()
        for (file in inspected) {
            when (file) {
                is PlaceholderFile -> skipped += SkippedFile(file.name, "The file is not on this device yet.")
                is InvalidFile -> skipped += SkippedFile(file.name, file.reason)
                is TooNewFile -> skipped += SkippedFile(file.name, "This file is from a newer version of Lynx.")
                is ValidFile -> if (file.libraryId != null && file.libraryId != chosen) {
                    skipped += SkippedFile(file.name, "This file belongs to a different library.")
                }
            }
        }

        openWork()
        for (file in valid) {
            if (file.libraryId != null && file.libraryId != chosen) continue
            openCopy(file.bytes).use { peer ->
                Merger.mergePeer(database!!, peer.db)
            }
        }
        Merger.collapseTags(database!!, clock.epochMs(), deviceId)

        val own = valid.find { it.name == fileName }
        if (own != null && own.deviceId != null && own.deviceId != deviceId) {
            return OpenResult.PrefixInUse(fileName, suggestedPrefix(prefix, deviceId))
        }
        publishWork()
        return OpenResult.Ready(chosen, skipped)
    }

    fun publish(): OpenResult = sync()

    fun sync(): OpenResult {
        check(database != null) { "Open the library before syncing." }
        return refresh(publishOwnFile = true)
    }

    fun close() {
        driver?.close()
        driver = null
        database = null
    }

    private fun refresh(publishOwnFile: Boolean): OpenResult {
        val inspected = inspectFolder()
        val valid = inspected.filterIsInstance<ValidFile>()
        val chosen = libraryId ?: error("Library id is missing.")
        for (file in valid) {
            if (file.libraryId != null && file.libraryId != chosen) continue
            openCopy(file.bytes).use { peer ->
                Merger.mergePeer(database!!, peer.db)
            }
        }
        Merger.collapseTags(database!!, clock.epochMs(), deviceId)
        val own = valid.find { it.name == fileName }
        if (own != null && own.deviceId != null && own.deviceId != deviceId) {
            return OpenResult.PrefixInUse(fileName, suggestedPrefix(prefix, deviceId))
        }
        if (publishOwnFile) publishWork()
        return OpenResult.Ready(chosen, emptyList())
    }

    private fun openWork() {
        if (database != null) return
        val path = files.child(workFolder, "work.sqlite")
        val opened = files.openDriver(path)
        driver = opened
        opened.execute(null, "PRAGMA foreign_keys=ON", 0, null).value
        LynxDatabase.Schema.create(opened).value
        opened.execute(null, "PRAGMA journal_mode=WAL", 0, null).value
        opened.execute(null, "PRAGMA synchronous=FULL", 0, null).value
        database = LynxDatabase(opened)
        writeMeta("schema_version", SCHEMA_VERSION.toString())
        writeMeta("library_id", libraryId!!)
    }

    private fun publishWork() {
        val opened = driver ?: return
        writeMeta("schema_version", SCHEMA_VERSION.toString())
        writeMeta("library_id", libraryId!!)
        writeMeta("device_id", deviceId)
        writeMeta("device_name", deviceName)
        writeMeta("file_prefix", prefix)
        writeMeta("published_at", clock.epochMs().toString())
        files.checkpoint(opened)
        val bytes = files.read(files.child(workFolder, "work.sqlite"))
        check(bytes.isSqliteDatabase()) { "The working database is not a SQLite file." }
        files.replace(libraryFolder, fileName, bytes)
    }

    private fun writeMeta(key: String, value: String) {
        database!!.lynxQueries.upsertMeta(key, value)
    }

    private fun inspectFolder(): List<InspectedFile> {
        return files.listNames(libraryFolder)
            .filter { LYNX_FILE.matches(it) }
            .map { name -> inspect(name) }
    }

    private fun inspect(name: String): InspectedFile {
        val path = files.child(libraryFolder, name)
        val size = files.size(path)
        if (size == 0L) return PlaceholderFile(name)
        val bytes = files.read(path)
        if (!bytes.isSqliteDatabase()) return InvalidFile(name, "This file is not a Lynx database.")
        return try {
            openCopy(bytes).use { opened ->
                val version = opened.db.lynxQueries.selectMeta("schema_version").executeAsOneOrNull()?.toLongOrNull()
                if (version != null && version > SCHEMA_VERSION) return TooNewFile(name)
                if (version == null) return InvalidFile(name, "This file is not a Lynx database.")
                ValidFile(
                    name = name,
                    bytes = bytes,
                    libraryId = opened.db.lynxQueries.selectMeta("library_id").executeAsOneOrNull(),
                    deviceId = opened.db.lynxQueries.selectMeta("device_id").executeAsOneOrNull(),
                )
            }
        } catch (_: Exception) {
            InvalidFile(name, "This file is not a Lynx database.")
        }
    }

    private fun openCopy(bytes: ByteArray): OpenedDatabase {
        val path = files.child(files.tempDirectory(), "peer.sqlite")
        files.write(path, bytes)
        val driver = files.openDriver(path)
        driver.execute(null, "PRAGMA foreign_keys=ON", 0, null).value
        return OpenedDatabase(driver, LynxDatabase(driver))
    }

    private class OpenedDatabase(val driver: SqlDriver, val db: LynxDatabase) : AutoCloseable {
        override fun close() = driver.close()
    }

    private sealed interface InspectedFile
    private data class PlaceholderFile(val name: String) : InspectedFile
    private data class InvalidFile(val name: String, val reason: String) : InspectedFile
    private data class TooNewFile(val name: String) : InspectedFile
    private data class ValidFile(
        val name: String,
        val bytes: ByteArray,
        val libraryId: String?,
        val deviceId: String?,
    ) : InspectedFile

    private companion object {
        val LYNX_FILE = Regex(".+_lynx\\.sqlite")
    }
}
