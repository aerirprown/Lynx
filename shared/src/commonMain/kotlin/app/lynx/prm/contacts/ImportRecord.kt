package app.lynx.prm.contacts

data class SeenValue(val value: String, val label: String?)

data class SeenContact(
    val displayName: String,
    val organization: String?,
    val jobTitle: String?,
    val phones: List<SeenValue>,
    val emails: List<SeenValue>,
)

/**
 * What this install saw at its last contacts import, by lookup key. It lives outside
 * work.sqlite and is never published. Comparing against it, rather than against the
 * library, stops two phones that disagree about a person overwriting each other.
 */
class ImportRecord(private val people: MutableMap<String, SeenContact> = mutableMapOf()) {
    operator fun get(lookupKey: String): SeenContact? = people[lookupKey]

    operator fun set(lookupKey: String, seen: SeenContact) {
        people[lookupKey] = seen
    }

    fun encode(): String = people.entries.joinToString("\n") { (key, seen) ->
        listOf(
            key,
            seen.displayName,
            seen.organization.orEmpty(),
            seen.jobTitle.orEmpty(),
            encodeValues(seen.phones),
            encodeValues(seen.emails),
        ).joinToString(FIELD.toString())
    }

    companion object {
        private const val FIELD = '\u001F'
        private const val ITEM = '\u001E'
        private const val PART = '\u001D'

        fun decode(text: String?): ImportRecord {
            val people = mutableMapOf<String, SeenContact>()
            text.orEmpty().lineSequence().forEach { line ->
                val fields = line.split(FIELD)
                if (fields.size == 6) {
                    people[fields[0]] = SeenContact(
                        displayName = fields[1],
                        organization = fields[2].ifEmpty { null },
                        jobTitle = fields[3].ifEmpty { null },
                        phones = decodeValues(fields[4]),
                        emails = decodeValues(fields[5]),
                    )
                }
            }
            return ImportRecord(people)
        }

        /** Removes the separators the record uses, so a value reads back as it was stored. */
        fun clean(value: String): String = value.filterNot { it < ' ' }

        private fun encodeValues(values: List<SeenValue>): String =
            values.joinToString(ITEM.toString()) { "${it.value}$PART${it.label.orEmpty()}" }

        private fun decodeValues(text: String): List<SeenValue> {
            if (text.isEmpty()) return emptyList()
            return text.split(ITEM).map { item ->
                SeenValue(item.substringBefore(PART), item.substringAfter(PART, "").ifEmpty { null })
            }
        }
    }
}
