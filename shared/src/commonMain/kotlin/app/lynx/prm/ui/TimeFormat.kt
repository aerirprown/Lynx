package app.lynx.prm.ui

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

fun formatWhen(epochMs: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    return "%04d-%02d-%02d %02d:%02d".format(
        local.year,
        local.monthNumber,
        local.dayOfMonth,
        local.hour,
        local.minute,
    )
}

fun parseWhen(text: String): Long? {
    val trimmed = text.trim().replace(' ', 'T')
    if (trimmed.isEmpty()) return null
    val withSeconds = if (trimmed.count { it == ':' } == 1) "$trimmed:00" else trimmed
    return try {
        LocalDateTime.parse(withSeconds).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }
}
