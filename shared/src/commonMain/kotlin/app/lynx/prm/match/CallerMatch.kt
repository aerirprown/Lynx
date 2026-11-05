package app.lynx.prm.match

data class MatchableContact(
    val id: String,
    val displayName: String,
    val numbers: List<String>,
)

private val genericTitles = setOf(
    "ongoing call",
    "on-going call",
    "current call",
    "phone",
    "call",
    "call in progress",
    "mobile",
    "dialing",
)

fun isGenericTitle(title: String): Boolean = title.trim().foldCase() in genericTitles

fun matchCaller(
    title: String,
    contacts: List<MatchableContact>,
    defaultRegion: String,
): List<MatchableContact> {
    val trimmed = title.trim()
    if (trimmed.isEmpty() || isGenericTitle(trimmed)) return emptyList()
    if (PhoneNumbers.isPhoneTitle(trimmed)) {
        val normalized = PhoneNumbers.normalize(trimmed, defaultRegion)
        return contacts.filter { contact ->
            contact.numbers.any { PhoneNumbers.matches(it, normalized) }
        }
    }
    return contacts.filter { it.displayName.trim().foldCase() == trimmed.foldCase() }
}

internal fun String.foldCase(): String = map { char ->
    when (char) {
        in 'A'..'Z' -> char + ('a' - 'A')
        else -> char
    }
}.joinToString("")
