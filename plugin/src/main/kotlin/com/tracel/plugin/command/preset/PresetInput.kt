package com.tracel.plugin.command.preset

import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.lookup.filledFrom
import com.tracel.plugin.command.args.lookup.parseLookupArgs
import com.tracel.plugin.i18n.tr
import java.util.*

/**
 * Parses flag tokens where any `@name` stands for a saved preset.
 *
 * What is typed wins: `@grief t:30m` is the preset with its time replaced. Presets fill only what is still empty,
 * left to right.
 */
internal fun parseWithPresets(
    tokens: List<String>,
    owner: UUID?,
    store: PresetStore?,
    now: Long,
): ParsedLookupArgs {
    val (refs, typed) = tokens.partition { it.startsWith("@") && it.length > 1 }
    var result = parseLookupArgs(typed, now)
    for (ref in refs) {
        val preset = store?.find(ref.substring(1), owner)
        result = if (preset == null) {
            result.copy(errors = result.errors + tr("preset.reason.missing", "name" to ref.substring(1).lowercase()))
        } else {
            result.filledFrom(parseLookupArgs(preset.tokens, now))
        }
    }
    return result
}
