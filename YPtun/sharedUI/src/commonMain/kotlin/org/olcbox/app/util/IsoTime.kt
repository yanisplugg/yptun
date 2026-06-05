package org.olcbox.app.util

import kotlinx.datetime.offsetIn

/**
 * Tiny dependency-free date helper for subscription metadata: parses the ISO-8601 `expiresAt` the
 * panel returns and formats an epoch-ms back into a `DD.MM.YYYY` calendar date. Uses Howard Hinnant's
 * civil↔days algorithm so it needs neither kotlinx-datetime nor any platform calendar API (works in
 * commonMain). Dates are computed in UTC — for a day-granularity subscription expiry that's all that
 * matters, and it avoids dragging timezone state into the shared module.
 */
object IsoTime {

    private const val MS_PER_DAY = 86_400_000L

    /** Days since 1970-01-01 for a proleptic-Gregorian (y, m, d); m in 1..12, d in 1..31. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400 // [0, 399]
        val mp = if (month > 2) month - 3 else month + 9 // Mar=0 .. Feb=11
        val doy = (153L * mp + 2) / 5 + day - 1 // [0, 365]
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy // [0, 146096]
        return era * 146097 + doe - 719468
    }

    /** Inverse of [daysFromCivil]: days-since-epoch → (year, month, day). */
    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097 // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
        val mp = (5 * doy + 2) / 153 // [0, 11]
        val day = (doy - (153 * mp + 2) / 5 + 1).toInt() // [1, 31]
        val month = (if (mp < 10) mp + 3 else mp - 9).toInt() // [1, 12]
        val year = (if (month <= 2) y + 1 else y).toInt()
        return Triple(year, month, day)
    }

    /**
     * Parses an ISO-8601 instant like `2099-05-03T20:59:00.000Z` (also accepts a space separator, a
     * missing time, fractional seconds, and a `±hh:mm` / `±hhmm` / `Z` offset) into epoch-ms, or null
     * when it isn't a recognisable date.
     */
    fun parseIsoToEpochMs(iso: String?): Long? {
        val s = iso?.trim().orEmpty()
        if (s.length < 10) return null
        return runCatching {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()

            var hour = 0
            var minute = 0
            var second = 0
            var offsetMinutes = 0

            if (s.length > 11 && (s[10] == 'T' || s[10] == 't' || s[10] == ' ')) {
                var rest = s.substring(11)
                when {
                    rest.endsWith("Z") || rest.endsWith("z") -> rest = rest.dropLast(1)
                    else -> {
                        // A trailing numeric timezone offset (the leading date '-' is already past us).
                        val sign = maxOf(rest.lastIndexOf('+'), rest.lastIndexOf('-'))
                        if (sign > 0) {
                            val zone = rest.substring(sign + 1).replace(":", "")
                            rest = rest.substring(0, sign)
                            if (zone.length >= 2) {
                                val oh = zone.substring(0, 2).toInt()
                                val om = if (zone.length >= 4) zone.substring(2, 4).toInt() else 0
                                val s2 = if (s[11 + sign] == '-') -1 else 1
                                offsetMinutes = s2 * (oh * 60 + om)
                            }
                        }
                    }
                }
                val hms = rest.substringBefore('.').split(':')
                hour = hms.getOrNull(0)?.toIntOrNull() ?: 0
                minute = hms.getOrNull(1)?.toIntOrNull() ?: 0
                second = hms.getOrNull(2)?.toIntOrNull() ?: 0
            }

            val epochSec = daysFromCivil(year, month, day) * 86_400L +
                hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
            epochSec * 1_000L
        }.getOrNull()
    }

    /** Formats an epoch-ms as a `DD.MM.YYYY` calendar date (UTC). */
    fun formatDate(epochMs: Long): String {
        val (year, month, day) = civilFromDays(epochMs.floorDiv(MS_PER_DAY))
        return "${pad2(day)}.${pad2(month)}.$year"
    }

    /** Formats an epoch-ms as `DD.MM.YYYY HH:MM:SS` (UTC, matching the panel's stored expiry). */
    fun formatDateTime(epochMs: Long): String {
        val (year, month, day) = civilFromDays(epochMs.floorDiv(MS_PER_DAY))
        val secOfDay = epochMs.mod(MS_PER_DAY) / 1_000L
        val hh = (secOfDay / 3_600L).toInt()
        val mm = ((secOfDay % 3_600L) / 60L).toInt()
        val ss = (secOfDay % 60L).toInt()
        return "${pad2(day)}.${pad2(month)}.$year ${pad2(hh)}:${pad2(mm)}:${pad2(ss)}"
    }

    /**
     * Same `DD.MM.YYYY HH:MM:SS` but in the device's local time zone, so subscription dates match what
     * the rest of the app shows (the panel sends an absolute instant; rendering it in UTC was 3 h off
     * for MSK users). Reuses the civil math by shifting the instant by the current UTC offset.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun formatLocalDateTime(epochMs: Long): String {
        val instant = kotlin.time.Instant.fromEpochMilliseconds(epochMs)
        val offsetSeconds = instant
            .offsetIn(kotlinx.datetime.TimeZone.currentSystemDefault())
            .totalSeconds
        return formatDateTime(epochMs + offsetSeconds * 1_000L)
    }

    private fun pad2(value: Int): String = if (value in 0..9) "0$value" else value.toString()
}
