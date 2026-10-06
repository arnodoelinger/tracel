package com.tracel.plugin.command.suggest.flag

import com.tracel.plugin.command.args.time.TimeArgument
import com.tracel.plugin.command.suggest.quantity.QuantityUnit
import com.tracel.plugin.command.suggest.quantity.ago
import com.tracel.plugin.command.suggest.quantity.around
import com.tracel.plugin.command.suggest.quantity.past
import com.tracel.plugin.command.suggest.quantity.span
import com.tracel.plugin.i18n.joined
import com.tracel.plugin.i18n.tr
import net.kyori.adventure.text.Component

internal val TIME_PRESETS = listOf("10s", "30s", "1m", "5m", "10m", "30m", "1h", "3h", "6h", "12h", "1d", "3d", "7d")

internal val NAMED_DAYS = listOf(
    "today" to tr("suggest.today"),
    "yesterday" to tr("suggest.yesterday"),
)

internal val SCOPE_PRESETS = listOf("4b", "8b", "16b", "32b", "64b", "128b", "1c", "2c", "4c", "8c")

internal val WINDOW_UNITS = listOf(
    QuantityUnit("s") { past(it, "second") },
    QuantityUnit("m") { past(it, "minute") },
    QuantityUnit("h") { past(it, "hour") },
    QuantityUnit("d") { past(it, "day") },
    QuantityUnit("w") { past(it, "week") },
)

internal val POINT_UNITS = listOf(
    QuantityUnit("s") { ago(it, "second") },
    QuantityUnit("m") { ago(it, "minute") },
    QuantityUnit("h") { ago(it, "hour") },
    QuantityUnit("d") { ago(it, "day") },
    QuantityUnit("w") { ago(it, "week") },
)

internal val SCOPE_UNITS = listOf(
    QuantityUnit("b") { around(it, "block") },
    QuantityUnit("c") { around(it, "chunk") },
)

private val SPAN_PARTS = listOf(
    604_800_000L to "week",
    86_400_000L to "day",
    3_600_000L to "hour",
    60_000L to "minute",
    1_000L to "second",
)

internal fun spanOf(text: String): Component? {
    var left = TimeArgument.parseDuration(text) ?: return null
    val parts = ArrayList<Component>()
    for ((millis, noun) in SPAN_PARTS) {
        val n = left / millis
        left %= millis
        if (n > 0) parts += span(n, noun)
    }
    return if (parts.isEmpty()) null else parts.joined(" ")
}

private val ACTION_TIPS = mapOf(
    "block" to "block",
    "+block" to "place",
    "place" to "place",
    "-block" to "break",
    "break" to "break",
    "sign" to "sign",
    "entity" to "entity",
    "+entity" to "spawn",
    "-entity" to "kill",
    "kill" to "kill",
    "container" to "container",
    "item" to "container",
    "inventory" to "container",
    "craft" to "craft",
    "explosion" to "explosion",
    "click" to "click",
    "chat" to "chat",
    "command" to "command",
    "session" to "session",
    "+session" to "join",
    "join" to "join",
    "-session" to "quit",
    "quit" to "quit",
    "death" to "death",
)

internal fun actionTip(name: String): Component =
    ACTION_TIPS[name]?.let { tr("suggest.action.$it") } ?: Component.text(name)

internal val WHERE_WORDS = listOf(
    "block" to tr("suggest.scope_block"),
    "chunk" to tr("suggest.scope_chunk"),
)
