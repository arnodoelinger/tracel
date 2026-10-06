package com.tracel.plugin.command.preset

import java.util.*

/** A saved set of flags. [owner] `null` is the server's own: everyone sees it, a personal one of the same name wins. */
internal data class Preset(val name: String, val owner: UUID?, val tokens: List<String>) {
    val text: String get() = tokens.joinToString(" ")
}
