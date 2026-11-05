package app.lynx.prm.library

import app.cash.sqldelight.db.SqlDriver

interface PlatformFiles {
    fun tempDirectory(): String
    fun child(parent: String, name: String): String
    fun listNames(directory: String): List<String>
    fun exists(path: String): Boolean
    fun size(path: String): Long
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun replace(directory: String, fileName: String, bytes: ByteArray)
    fun openDriver(path: String): SqlDriver
    fun checkpoint(driver: SqlDriver)
}

expect fun platformFiles(): PlatformFiles
