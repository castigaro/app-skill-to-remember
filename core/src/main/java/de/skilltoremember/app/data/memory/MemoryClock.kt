package de.skilltoremember.app.data.memory

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Aktuelle Zeit, überschreibbar für deterministische Tests — Pendant zu
 * `set_now()`/`now()` in `memory.py`.
 */
object MemoryClock {

    private var override: Instant? = null

    /** Nur für Tests: friert die Uhr auf einen festen Zeitpunkt ein. */
    fun freeze(instant: Instant?) {
        override = instant
    }

    fun now(): Instant = override ?: Instant.now()

    fun isoNow(): String = iso(now())

    fun iso(instant: Instant): String =
        DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS))

    fun parseIso(text: String): Instant = Instant.parse(text)

    fun daysBetween(later: Instant, earlier: Instant): Double =
        maxOf(0.0, ChronoUnit.SECONDS.between(earlier, later) / 86400.0)
}
