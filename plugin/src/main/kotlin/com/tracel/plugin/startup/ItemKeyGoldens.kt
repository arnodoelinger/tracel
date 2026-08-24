package com.tracel.plugin.startup

/**
 * One representative decorated item's expected content hash, checked in
 * at some baseline Minecraft version.
 */
data class Golden(val name: String, val expectedHex: String)

sealed interface GoldenResult {
    val name: String

    /** [actualHex] matched the checked-in golden — the wire format hasn't moved under us. */
    data class Match(override val name: String) : GoldenResult

    /**
     * `ItemStack.serializeAsBytes()` no longer produces what it did at the version [expectedHex]
     * was captured at — every already-recorded `item_key` for this shape of item just stopped
     * matching newly captured ones.
     */
    data class Drifted(override val name: String, val expectedHex: String, val actualHex: String) : GoldenResult

    /** No golden checked in yet for [name] — first run on a new representative item, not a failure. */
    data class Unrecorded(override val name: String, val actualHex: String) : GoldenResult
}

/** Parses the `name=hex` golden file format — blank lines and `#`-comments ignored. */
fun parseGoldens(text: String): List<Golden> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { line ->
            val (name, hex) = line.split("=", limit = 2).map(String::trim)
            Golden(name, hex)
        }
        .toList()

/** Compares freshly computed [actual] hashes (name -> hex) against checked-in [goldens]. */
fun checkGoldens(goldens: List<Golden>, actual: Map<String, String>): List<GoldenResult> {
    val known = goldens.associateBy { it.name }
    return actual.map { (name, hex) ->
        val golden = known[name]
        when {
            golden == null -> GoldenResult.Unrecorded(name, hex)
            golden.expectedHex == hex -> GoldenResult.Match(name)
            else -> GoldenResult.Drifted(name, golden.expectedHex, hex)
        }
    }
}
