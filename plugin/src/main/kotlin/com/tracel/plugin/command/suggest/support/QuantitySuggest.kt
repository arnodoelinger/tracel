package com.tracel.plugin.command.suggest.support

import com.tracel.plugin.command.suggest.Suggestion
import com.tracel.plugin.command.suggest.rank

internal data class QuantityUnit(
    val suffix: String,
    val describe: (Long) -> String,
)

private val SIMPLE = Regex("""^(\d+)([a-z]*)$""")
private val COMPOUND = Regex("""^((?:\d+[smhdw])*)(\d+)([a-z]*)$""")

/** Suggestions for a number-then-unit value. */
internal fun suggestQuantity(
    prefix: String,
    raw: String,
    units: List<QuantityUnit>,
    presets: List<Pair<String, String>>,
    words: List<Pair<String, String>> = emptyList(),
    compound: Boolean = false,
): List<Suggestion> {
    val needle = raw.lowercase()
    val match = (if (compound) COMPOUND else SIMPLE).matchEntire(needle)
    if (match != null) {
        val head = if (compound) match.groupValues[1] else ""
        val number = if (compound) match.groupValues[2] else match.groupValues[1]
        val typedUnit = if (compound) match.groupValues[3] else match.groupValues[2]
        val fromUnits = unitSuggestions(prefix, head, number, typedUnit, units, presets)
        val fromDigits = digitSuggestions(prefix, head, number, typedUnit)
        val taken = (fromUnits + fromDigits).map { it.text }.toSet()
        return fromUnits + fromDigits +
                presetSuggestions(prefix, needle, presets).filter { it.text !in taken } +
                wordSuggestions(prefix, needle, words)
    }
    val open = if (needle.isEmpty()) digitSuggestions(prefix, "", "", "") else emptyList()
    val taken = open.map { it.text }.toSet()
    return open +
            presetSuggestions(prefix, needle, presets).filter { it.text !in taken } +
            wordSuggestions(prefix, needle, words)
}

private fun digitSuggestions(
    prefix: String,
    head: String,
    number: String,
    typedUnit: String,
): List<Suggestion> {
    if (typedUnit.isNotEmpty() || number.length >= 2) return emptyList()
    return (0..9).map { digit -> Suggestion("$prefix$head$number$digit") }
}

private fun unitSuggestions(
    prefix: String,
    head: String,
    number: String,
    typedUnit: String,
    units: List<QuantityUnit>,
    presets: List<Pair<String, String>>,
): List<Suggestion> {
    val amount = number.toLongOrNull() ?: return emptyList()
    return units
        .filter { it.suffix.startsWith(typedUnit) }
        .map { unit ->
            val value = "$head$number${unit.suffix}"
            val preset = presets.firstOrNull { it.first.equals(value, ignoreCase = true) }?.second
            Suggestion(
                text = "$prefix$value",
                tooltip = preset ?: unit.describe(amount),
            )
        }
}

private fun presetSuggestions(
    prefix: String,
    needle: String,
    presets: List<Pair<String, String>>,
): List<Suggestion> = presets
    .filter { needle.isEmpty() || it.first.startsWith(needle) }
    .map { Suggestion("$prefix${it.first}", it.second) }

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
