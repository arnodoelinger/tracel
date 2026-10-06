package com.tracel.plugin.command.preset

/** The store the commands and their completions share; set when the commands are registered. */
internal object Presets {
    @Volatile
    var store: PresetStore? = null
}
