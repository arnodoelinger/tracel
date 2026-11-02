package com.tracel.plugin.bukkit.command

/** Vanilla commands that change what a player holds without an inventory event. */
internal enum class GapCommand(val literal: String) {
    GIVE("give"),
    CLEAR("clear"),
    ITEM("item");

    companion object {
        val literals: Set<String> = entries.mapTo(HashSet()) { it.literal }
    }
}
