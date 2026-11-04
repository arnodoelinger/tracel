package com.tracel.plugin.util.units

internal const val MILLIS_PER_SECOND = 1_000L
internal const val MILLIS_PER_MINUTE = 60 * MILLIS_PER_SECOND
internal const val MILLIS_PER_HOUR = 60 * MILLIS_PER_MINUTE
internal const val MILLIS_PER_DAY = 24 * MILLIS_PER_HOUR
internal const val MILLIS_PER_WEEK = 7 * MILLIS_PER_DAY

private val DURATION = Regex("""((\d+)(ms|[smhdw]))+""")
private val DURATION_PART = Regex("""(\d+)(ms|[smhdw])""")

/** `1h30m` reads as ninety minutes. An overflow is no duration, not a window that wraps into the future. */
internal fun parseDuration(text: String): Long? {
    if (!DURATION.matches(text)) return null
    var total = 0L
    for (part in DURATION_PART.findAll(text)) {
        val (amount, unit) = part.destructured
        val n = amount.toLongOrNull() ?: return null
        val unitMillis = when (unit) {
            "ms" -> 1L
            "s" -> MILLIS_PER_SECOND
            "m" -> MILLIS_PER_MINUTE
            "h" -> MILLIS_PER_HOUR
            "d" -> MILLIS_PER_DAY
            "w" -> MILLIS_PER_WEEK
            else -> return null
        }
        total = runCatching { Math.addExact(total, Math.multiplyExact(n, unitMillis)) }.getOrNull() ?: return null
    }
    return total
}

/** `90s`, `12m`, `3h`, `2d`: the biggest unit that is still at least one. */
internal fun shortSpan(millis: Long): String {
    val s = millis / MILLIS_PER_SECOND
    return when {
        s < 60 -> "${maxOf(s, 1)}s"
        s < 3_600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3_600}h"
        else -> "${s / 86_400}d"
    }
}
