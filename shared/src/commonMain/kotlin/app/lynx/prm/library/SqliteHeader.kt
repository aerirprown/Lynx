package app.lynx.prm.library

fun ByteArray.isSqliteDatabase(): Boolean {
    if (size < 16) return false
    val header = "SQLite format 3".encodeToByteArray()
    return copyOfRange(0, header.size).contentEquals(header) && this[header.size] == 0.toByte()
}
