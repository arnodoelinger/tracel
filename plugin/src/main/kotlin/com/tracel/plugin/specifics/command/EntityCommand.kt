package com.tracel.plugin.specifics.command

/** Vanilla commands that make or remove entities; whoever ran one is to blame for what follows. */
internal enum class EntityCommand(val literal: String) {
    SUMMON("summon"),
    KILL("kill"),
}
