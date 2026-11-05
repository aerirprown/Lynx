package app.lynx.prm.match

import app.lynx.prm.store.LynxStore

data class IncomingNotification(
    val key: String,
    val packageName: String,
    val category: String?,
    val title: String,
    val text: String,
    val postedAtEpochMs: Long,
    val ongoingCallStyle: Boolean = false,
    val defaultDialerPackage: String? = null,
)

data class CallPrompt(
    val title: String,
    val message: String,
    val contactIds: List<String>,
    val occurredAtEpochMs: Long,
    val direction: String,
    val callNumberRaw: String?,
    val callNumberNormalized: String?,
)

class CallPromptController(
    private val contacts: () -> List<MatchableContact>,
    private val defaultRegion: String,
) {
    private val active = mutableMapOf<String, IncomingNotification>()
    private val promptedAtByTitle = mutableMapOf<String, Long>()

    fun onPosted(notification: IncomingNotification, nowEpochMs: Long): CallPrompt? {
        if (!accepts(notification)) return null
        active[notification.key] = notification
        if (notification.category != MISSED) return null
        return ask(notification, nowEpochMs, missed = true)
    }

    fun onRemoved(key: String, nowEpochMs: Long): CallPrompt? {
        val notification = active.remove(key) ?: return null
        if (nowEpochMs - notification.postedAtEpochMs < MIN_POSTED_MS) return null
        return ask(notification, nowEpochMs, missed = notification.category == MISSED)
    }

    fun notNow() = Unit

    fun confirm(prompt: CallPrompt, contactId: String, body: String, store: LynxStore): String {
        require(body.isNotBlank()) { "A note needs text." }
        return store.addNote(
            contactId = contactId,
            body = body,
            source = "call_prompt",
            occurredAt = prompt.occurredAtEpochMs,
            direction = prompt.direction,
            callNumberRaw = prompt.callNumberRaw,
            callNumberNormalized = prompt.callNumberNormalized,
        )
    }

    private fun ask(notification: IncomingNotification, nowEpochMs: Long, missed: Boolean): CallPrompt? {
        val previous = promptedAtByTitle[notification.title]
        if (previous != null && nowEpochMs - previous < DEDUPE_MS) return null
        promptedAtByTitle[notification.title] = nowEpochMs
        val matches = matchCaller(notification.title, contacts(), defaultRegion)
        val phoneTitle = PhoneNumbers.isPhoneTitle(notification.title) && !isGenericTitle(notification.title)
        val who = when {
            matches.size == 1 -> matches.single().displayName
            phoneTitle -> notification.title.trim()
            else -> null
        }
        val message = when {
            missed && who != null -> "Add a note about a missed call from $who?"
            !missed && matches.size == 1 -> "Add a note about your call with ${matches.single().displayName}?"
            !missed && who != null -> "Add a note about your call with $who?"
            else -> "Add a note about the call that just ended?"
        }
        val normalized = if (phoneTitle) PhoneNumbers.normalize(notification.title, defaultRegion) else null
        return CallPrompt(
            title = notification.title,
            message = message,
            contactIds = matches.map { it.id },
            occurredAtEpochMs = if (missed) nowEpochMs else notification.postedAtEpochMs,
            direction = if (missed) "missed" else "unknown",
            callNumberRaw = if (phoneTitle) notification.title.trim() else null,
            callNumberNormalized = normalized,
        )
    }

    private fun accepts(notification: IncomingNotification): Boolean {
        val known = notification.packageName in DIALER_PACKAGES ||
            (notification.defaultDialerPackage != null && notification.packageName == notification.defaultDialerPackage)
        if (!known) return false
        return notification.category == CALL ||
            notification.category == MISSED ||
            notification.ongoingCallStyle
    }

    private companion object {
        const val CALL = "call"
        const val MISSED = "missed_call"
        const val MIN_POSTED_MS = 2_000L
        const val DEDUPE_MS = 60_000L
        val DIALER_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.samsung.android.incallui",
        )
    }
}
