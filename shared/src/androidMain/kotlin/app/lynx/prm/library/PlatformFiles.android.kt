package app.lynx.prm.library

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.lynx.prm.db.LynxDatabase
import java.io.File

class AndroidPlatformFiles(private val context: Context) : PlatformFiles {
    override fun tempDirectory(): String {
        val dir = File(context.cacheDir, "lynx-${System.nanoTime()}")
        dir.mkdirs()
        return dir.absolutePath
    }

    override fun child(parent: String, name: String): String {
        val tree = treeUri(parent)
        return if (tree == null) File(parent, name).absolutePath else "saf:$tree|$name"
    }

    override fun listNames(directory: String): List<String> {
        val tree = treeUri(directory) ?: return listFiles(directory)
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: return emptyList()
        return folder.listFiles().mapNotNull { it.name }
    }

    override fun exists(path: String): Boolean {
        val document = document(path)
        return if (document != null) document.exists() else File(path).exists()
    }

    override fun size(path: String): Long {
        val document = document(path)
        return when {
            document != null -> if (document.exists()) document.length() else 0L
            else -> File(path).takeIf { it.exists() }?.length() ?: 0L
        }
    }

    override fun read(path: String): ByteArray {
        val document = document(path)
        if (document == null) return File(path).readBytes()
        return context.contentResolver.openInputStream(document.uri)?.use { it.readBytes() }
            ?: error("Could not read ${document.name}.")
    }

    override fun write(path: String, bytes: ByteArray) {
        val located = safLocation(path)
        if (located == null) {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            return
        }
        writeDocument(located.first, located.second, bytes)
    }

    override fun delete(path: String) {
        val document = document(path)
        when {
            document != null -> if (document.exists()) document.delete()
            safLocation(path) == null -> SQLiteDatabase.deleteDatabase(File(path))
        }
    }

    override fun replace(directory: String, fileName: String, bytes: ByteArray) {
        val tree = treeUri(directory)
        if (tree == null) {
            JvmStyleReplace.replace(directory, fileName, bytes)
            return
        }
        val tmpName = "$fileName.tmp"
        writeDocument(tree, tmpName, bytes)
        val written = read(child(tree, tmpName))
        check(written.isNotEmpty() && written.isSqliteDatabase()) {
            "Refusing to publish a file that is not a SQLite database."
        }
        writeDocument(tree, fileName, written)
        document(child(tree, tmpName))?.delete()
    }

    override fun openWorkDriver(path: String): SqlDriver {
        check(safLocation(path) == null) { "SQLite is only opened on the private working copy." }
        File(path).parentFile?.mkdirs()
        return AndroidSqliteDriver(LynxDatabase.Schema, context, path)
    }

    override fun openPeerDriver(path: String): SqlDriver {
        check(safLocation(path) == null) { "SQLite is only opened on a private copy." }
        return AndroidSqliteDriver(PeerSchema, context, path)
    }

    override fun checkpoint(driver: SqlDriver) {
        driver.executeQuery(
            null,
            "PRAGMA wal_checkpoint(TRUNCATE)",
            { cursor ->
                cursor.next()
                QueryResult.Unit
            },
            0,
            null,
        ).value
    }

    private fun writeDocument(tree: String, name: String, bytes: ByteArray) {
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(tree)) ?: error("The folder is not available.")
        val existing = folder.findFile(name)
        val document = existing ?: folder.createFile("application/octet-stream", name)
            ?: error("Could not create $name.")
        context.contentResolver.openOutputStream(document.uri, "wt")?.use { it.write(bytes) }
            ?: error("Could not write $name.")
    }

    private fun document(path: String): DocumentFile? {
        val located = safLocation(path) ?: return null
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(located.first)) ?: return null
        return folder.findFile(located.second)
    }

    private fun listFiles(directory: String): List<String> {
        val dir = File(directory)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
    }

    private fun treeUri(path: String): String? = when {
        path.startsWith("content:") -> path
        else -> safLocation(path)?.first
    }

    private fun safLocation(path: String): Pair<String, String>? {
        if (!path.startsWith("saf:")) return null
        val rest = path.removePrefix("saf:")
        val bar = rest.lastIndexOf('|')
        if (bar <= 0) return null
        return rest.substring(0, bar) to rest.substring(bar + 1)
    }
}

/**
 * A published file already has its tables, and its SQLite version number depends on the
 * device that wrote it. The desktop leaves it at 0, which Android would otherwise take for
 * a new database. This schema stands above any version a file can carry and changes nothing.
 */
private object PeerSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1_000_000

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

private object JvmStyleReplace {
    fun replace(directory: String, fileName: String, bytes: ByteArray) {
        val dir = File(directory)
        dir.mkdirs()
        val tmp = File(dir, "$fileName.tmp")
        val dest = File(dir, fileName)
        tmp.writeBytes(bytes)
        val written = tmp.readBytes()
        check(written.isSqliteDatabase()) { "Refusing to publish a file that is not a SQLite database." }
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(written)
            tmp.delete()
        }
    }
}

actual fun platformFiles(): PlatformFiles = error("Android creates its file system with a Context.")
