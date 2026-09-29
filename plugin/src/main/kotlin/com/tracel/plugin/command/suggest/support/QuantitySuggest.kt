package com.tracel.plugin.command.suggest.support

import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.rank

internal data class QuantityUnit(
    val suffix: String,
    val describe: (Long) -> String,
)

private val SIMPLE = Regex("""^(\d+)([a-z]*)$""")
private val COMPOUND = Regex("""^((?:\d+[smhdw])*)(\d+)([a-z]*)$""")
private val COMPLETE = Regex("""^(?:\d+[smhdw])+$""")
private val PRESET = Regex("""^(\d+)([a-z])$""")

/** Suggestions for a number-then-unit value, in the order they should appear. */
internal fun suggestQuantity(
    prefix: String,
    raw: String,
    units: List<QuantityUnit>,
    presets: List<Pair<String, String>>,
    words: List<Pair<String, String>> = emptyList(),
    compound: Boolean = false,
    allowed: (QuantityUnit, Long) -> Boolean = { _, _ -> true },
    describeWhole: (String) -> String? = { null },
): List<Suggestion> {
    val needle = raw.lowercase()
    val out = LinkedHashMap<String, Suggestion>()

    val match = (if (compound) COMPOUND else SIMPLE).matchEntire(needle)
    if (match != null) {
        val head = if (compound) match.groupValues[1] else ""
        val number = if (compound) match.groupValues[2] else match.groupValues[1]
        val typedUnit = if (compound) match.groupValues[3] else match.groupValues[2]
        for (suggestion in unitSuggestions(prefix, head, number, typedUnit, units, presets, allowed, describeWhole)) {
            out.putIfAbsent(suggestion.text, suggestion)
        }
    } else if (compound && COMPLETE.matches(needle)) {
        describeWhole(needle)?.let { out.putIfAbsent("$prefix$needle", Suggestion("$prefix$needle", it)) }
    }

    for ((first, second) in presets) {
        if (needle.isNotEmpty() && !first.startsWith(needle)) continue
        out.putIfAbsent("$prefix$first", Suggestion("$prefix$first", second))
    }
    for (word in wordSuggestions(prefix, needle, words)) out.putIfAbsent(word.text, word)
    return out.values.toList()
}

/** Presets built from [values] such as `5b` or `10m`, each described by its own unit. */
internal fun presetsOf(values: List<String>, units: List<QuantityUnit>): List<Pair<String, String>> =
    values.mapNotNull { value ->
        val (amount, suffix) = PRESET.matchEntire(value)?.destructured ?: return@mapNotNull null
        val unit = units.firstOrNull { it.suffix == suffix } ?: return@mapNotNull null
        value to unit.describe(amount.toLong())
    }

private fun unitSuggestions(
    prefix: String,
    head: String,
    number: String,
    typedUnit: String,
    units: List<QuantityUnit>,
    presets: List<Pair<String, String>>,
    allowed: (QuantityUnit, Long) -> Boolean,
    describeWhole: (String) -> String?,
): List<Suggestion> {
    val amount = number.toLongOrNull() ?: return emptyList()
    if (amount < 1) return emptyList()
    return units
        .filter { it.suffix.startsWith(typedUnit) && allowed(it, amount) }
        .map { unit ->
            val value = "$head$number${unit.suffix}"
            val preset = presets.firstOrNull { it.first.equals(value, ignoreCase = true) }?.second
            Suggestion(
                text = "$prefix$value",
                // After `1h`, `30m` alone would read as thirty minutes
                tooltip = preset ?: head.takeIf { it.isNotEmpty() }?.let { describeWhole(value) } ?: unit.describe(amount),
            )
        }
}

private fun wordSuggestions(
    prefix: String,
    needle: String,
    words: List<Pair<String, String>>,
): List<Suggestion> {
    val byName = words.associate { it.first to it.second }
    return rank(words.map { it.first }, needle, limit = 30).map { name ->
        Suggestion("$prefix$name", byName[name])
    }
}

internal fun around(amount: Long, noun: String): String =
    if (amount == 1L) "1 $noun around you" else "$amount ${noun}s around you"

internal fun ago(amount: Long, noun: String): String =
    if (amount == 1L) "1 $noun ago" else "$amount ${noun}s ago"

internal fun past(amount: Long, noun: String): String =
    if (amount == 1L) "Past 1 $noun" else "Past $amount ${noun}s"
