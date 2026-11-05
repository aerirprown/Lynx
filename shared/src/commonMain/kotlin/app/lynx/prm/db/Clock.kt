package app.lynx.prm.db

import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

fun interface Clock {
    fun now(): Instant
}

fun Clock.epochMs(): Long = now().toEpochMilliseconds()

class MutableClock(var millis: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

fun interface Ids {
    fun newId(): String
}

class UuidIds : Ids {
    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    override fun newId(): String = Uuid.random().toString()
}

class SequenceIds(ids: List<String>) : Ids {
    private val remaining = ArrayDeque(ids)
    override fun newId(): String = remaining.removeFirst()
}
