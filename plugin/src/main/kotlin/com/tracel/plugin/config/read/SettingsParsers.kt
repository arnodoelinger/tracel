package com.tracel.plugin.config.read

import com.tracel.engine.store.StoreSync

internal fun parseSync(value: String): StoreSync? =
    when (value.trim().lowercase()) {
        "every-batch" -> StoreSync.EveryBatch
        "never" -> StoreSync.Never
        else -> parseDuration(value)?.let(StoreSync::Interval)
    }

internal fun parseDuration(value: String): Long? {
    val text = value.trim().lowercase()

    val multiplier = when {
        text.endsWith("ms") -> 1L
        text.endsWith("s") -> 1_000L
        text.endsWith("m") -> 60_000L
        else -> return null
    }

    val number = text.removeSuffix("ms")
        .removeSuffix("s")
        .removeSuffix("m")
        .toLongOrNull()
        ?: return null

    return number.takeIf { it > 0 }?.times(multiplier)
}

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
