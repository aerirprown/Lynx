package app.lynx.prm.match

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

internal actual fun platformNormalize(raw: String, defaultRegion: String): String? {
    return try {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(raw, defaultRegion)
        if (!util.isValidNumber(parsed)) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    } catch (_: NumberParseException) {
        null
    }
}
