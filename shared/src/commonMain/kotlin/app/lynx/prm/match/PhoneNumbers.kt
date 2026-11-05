package app.lynx.prm.match

object PhoneNumbers {
    fun normalize(raw: String, defaultRegion: String): String {
        val parsed = platformNormalize(raw, defaultRegion)
        if (parsed != null) return parsed
        val digits = raw.filter { it.isDigit() }
        val keepPlus = raw.trimStart().startsWith("+") && digits.isNotEmpty()
        return if (keepPlus) "+$digits" else digits
    }

    fun matches(a: String, b: String): Boolean {
        if (a == b) return true
        val aDigits = a.filter { it.isDigit() }
        val bDigits = b.filter { it.isDigit() }
        return aDigits.length >= 10 && bDigits.length >= 10 && aDigits.takeLast(10) == bDigits.takeLast(10)
    }

    fun isPhoneTitle(title: String): Boolean {
        val stripped = title.filter { it !in PHONE_DECORATION }
        return PHONE_TITLE.matches(stripped)
    }

    private val PHONE_DECORATION = charArrayOf(' ', '-', '(', ')', '.', '\t')
    private val PHONE_TITLE = Regex("^\\+?\\d{7,}$")
}

internal expect fun platformNormalize(raw: String, defaultRegion: String): String?
