package app.lynx.prm.match

fun sanitizePrefix(raw: String): String {
    val kept = StringBuilder()
    for (char in raw.foldCase()) {
        when {
            char in 'a'..'z' || char in '0'..'9' -> kept.append(char)
            char.isWhitespace() -> kept.append(' ')
        }
    }
    val collapsed = kept.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString("_")
    val cut = collapsed.take(40).trim('_')
    return cut.ifEmpty { "lynx" }
}

fun lynxFileName(prefix: String): String = "${sanitizePrefix(prefix)}_lynx.sqlite"

fun suggestedPrefix(prefix: String, deviceId: String): String {
    val hex = deviceId.foldCase().filter { it in '0'..'9' || it in 'a'..'f' }.take(4).ifEmpty { "dev" }
    return sanitizePrefix("${sanitizePrefix(prefix)}_$hex")
}
