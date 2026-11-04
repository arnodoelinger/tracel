package com.tracel.plugin.command.args.time

import com.tracel.plugin.util.units.parseDuration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object TimeArgument {
    private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val DATE_TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?""")

    internal fun durationSuggestions(): List<String> = listOf("15m", "1h", "6h", "1d", "7d")

    internal fun timeSuggestions(): List<String> = durationSuggestions() + listOf("today", "yesterday")

    internal fun parseExpr(value: String, nowMillis: Long): TimeExpr? {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

        return when {
            value == "today" -> TimeExpr(startOfDay(today, zone), null)
            value == "yesterday" -> TimeExpr(startOfDay(today.minusDays(1), zone), startOfDay(today, zone) - 1)
            ".." in value -> {
                val (fromStr, toStr) = value.split("..", limit = 2)
                val from = parseTimePoint(fromStr, nowMillis, zone)
                val to = if (DATE_ONLY.matches(toStr)) {
                    runCatching { startOfDay(LocalDate.parse(toStr).plusDays(1), zone) - 1 }.getOrNull()
                } else {
                    parseTimePoint(toStr, nowMillis, zone)
                }
                if (from == null || to == null) null else TimeExpr(minOf(from, to), maxOf(from, to))
            }

            DATE_ONLY.matches(value) -> runCatching { LocalDate.parse(value) }.getOrNull()?.let {
                TimeExpr(startOfDay(it, zone), startOfDay(it.plusDays(1), zone) - 1)
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
