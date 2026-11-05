package app.lynx.prm.library

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.lynx.prm.db.LynxDatabase
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readBytes

internal class JvmPlatformFiles : PlatformFiles {
    override fun tempDirectory(): String = Files.createTempDirectory("lynx").absolutePathString()

    override fun child(parent: String, name: String): String = Path.of(parent, name).absolutePathString()

    override fun listNames(directory: String): List<String> {
        val path = Path.of(directory)
        if (!Files.isDirectory(path)) return emptyList()
        return Files.list(path).use { stream ->
            stream.filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.toList()
        }
    }

    override fun exists(path: String): Boolean = Path.of(path).exists()

    override fun size(path: String): Long {
        val file = Path.of(path)
        return if (file.exists()) file.fileSize() else 0L
    }

    override fun read(path: String): ByteArray = Path.of(path).readBytes()

    override fun write(path: String, bytes: ByteArray) {
        val file = Path.of(path)
        file.parent?.let { Files.createDirectories(it) }
        Files.write(file, bytes)
    }

    override fun delete(path: String) {
        Files.deleteIfExists(Path.of(path))
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            Files.deleteIfExists(Path.of(path + suffix))
        }
    }

    override fun replace(directory: String, fileName: String, bytes: ByteArray) {
        val dir = Path.of(directory)
        Files.createDirectories(dir)
        val tmp = dir.resolve("$fileName.tmp")
        val dest = dir.resolve(fileName)
        Files.write(tmp, bytes)
        val written = tmp.readBytes()
        check(written.isSqliteDatabase()) { "Refusing to publish a file that is not a SQLite database." }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun openWorkDriver(path: String): SqlDriver {
        // The driver opens a new connection per statement outside a transaction, and these
        // settings belong to a connection, so they have to come in with the connection.
        val settings = Properties().apply {
            setProperty("foreign_keys", "true")
            setProperty("synchronous", "FULL")
        }
        val driver = JdbcSqliteDriver(jdbcUrl(path), settings)
        val version = driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            0,
            null,
        ).value
        if (version == 0L) {
            LynxDatabase.Schema.create(driver).value
            driver.execute(null, "PRAGMA user_version = ${LynxDatabase.Schema.version}", 0, null).value
        }
        return driver
    }

    override fun openPeerDriver(path: String): SqlDriver = JdbcSqliteDriver(jdbcUrl(path))

    private fun jdbcUrl(path: String): String {
        val parent = Path.of(path).parent
        if (parent != null) Files.createDirectories(parent)
        return "jdbc:sqlite:${Path.of(path).absolutePathString().replace('\\', '/')}"
    }

    override fun checkpoint(driver: SqlDriver) {
        driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0, null).value
    }
}

actual fun platformFiles(): PlatformFiles = JvmPlatformFiles()
