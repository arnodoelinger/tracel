package com.tracel.plugin.specifics.command

/** Vanilla commands that write blocks without a block event. */
internal enum class StructureCommand(val literal: String) {
    SETBLOCK("setblock"),
    FILL("fill"),
    CLONE("clone");

    companion object {
        fun named(name: String?): StructureCommand? = entries.firstOrNull { it.literal == name }
    }
}
