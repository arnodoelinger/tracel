package com.tracel.plugin.util.units

import java.util.Locale

internal const val BYTES_PER_MIB = 1L shl 20
internal const val BYTES_PER_GIB = 1L shl 30

/** [this] in mebibytes. */
internal fun Long.toMib(): Double = this / BYTES_PER_MIB.toDouble()

/** [this] in gibibytes. */
internal fun Long.toGib(): Double = this / BYTES_PER_GIB.toDouble()

/** [bytes] as mebibytes with one decimal, the way [locale] writes numbers. */
internal fun formatMib(locale: Locale, bytes: Long): String = "%.1f".format(locale, bytes.toMib())

/** [n] with thousands separators, the way [locale] writes them. */
internal fun formatCount(locale: Locale, n: Long): String = "%,d".format(locale, n)

internal fun parseBytes(value: String): Long? {
    val text = value.trim().lowercase()

    val multiplier = when {
        text.endsWith("kib") || text.endsWith("kb") -> 1L shl 10
        text.endsWith("mib") || text.endsWith("mb") -> 1L shl 20
        text.endsWith("gib") || text.endsWith("gb") -> 1L shl 30
        text.endsWith("b") -> 1L
        else -> 1L
    }

    val number = text
        .removeSuffix("kib")
        .removeSuffix("kb")
        .removeSuffix("mib")
        .removeSuffix("mb")
        .removeSuffix("gib")
        .removeSuffix("gb")
        .removeSuffix("b")
        .trim()
        .toLongOrNull()
        ?: return null

    return number * multiplier
}
