package com.tracel.plugin.command.args

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

internal data class TimeExpr(val since: Long?, val until: Long?)

object TimeArgument {
    private val DURATION = Regex("""(\d+)([smhdw])""")
    private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?""")

    internal fun durationSuggestions(): List<String> = listOf("15m", "1h", "6h", "1d", "7d")

    internal fun timeSuggestions(): List<String> = durationSuggestions() + listOf("today", "yesterday")

    internal fun parseDuration(text: String): Long? {
        val match = DURATION.matchEntire(text) ?: return null
        val (amount, unit) = match.destructured
        val n = amount.toLongOrNull() ?: return null
        val unitMillis = when (unit) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            "w" -> 604_800_000L
            else -> return null
        }
        return n * unitMillis
    }

    internal fun parseExpr(value: String, nowMillis: Long): TimeExpr? {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        return when {
            value == "today" -> TimeExpr(startOfDay(today, zone), null)
            value == "yesterday" -> TimeExpr(startOfDay(today.minusDays(1), zone), startOfDay(today, zone))
            ".." in value -> {
                val (fromStr, toStr) = value.split("..", limit = 2)
                val from = parseTimePoint(fromStr, nowMillis, zone)
                val to = parseTimePoint(toStr, nowMillis, zone)?.let {
                    if (DATE_ONLY.matches(toStr)) it + 86_400_000L else it
                }
                if (from == null || to == null) null else TimeExpr(minOf(from, to), maxOf(from, to))
            }
            DATE_ONLY.matches(value) -> runCatching { LocalDate.parse(value) }.getOrNull()?.let {
                TimeExpr(startOfDay(it, zone), startOfDay(it.plusDays(1), zone))
            }
            else -> parseDuration(value)?.let { TimeExpr(nowMillis - it, nowMillis) }
        }
    }

    private fun startOfDay(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun parseTimePoint(text: String, nowMillis: Long, zone: ZoneId): Long? = when {
        DATE_TIME.matches(text) ->
            runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
        DATE_ONLY.matches(text) ->
            runCatching { startOfDay(LocalDate.parse(text), zone) }.getOrNull()
        else -> parseDuration(text)?.let { nowMillis - it }
    }
}
