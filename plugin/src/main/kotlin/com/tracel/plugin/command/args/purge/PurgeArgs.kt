package com.tracel.plugin.command.args.purge

import com.tracel.engine.store.PurgeCategory
import net.kyori.adventure.text.Component

/** What `/tracel data purge` was asked for, once its words are read. Names are still names: the action resolves them. */
internal data class PurgeArgs(
    val everything: Boolean = false,
    val categories: Set<PurgeCategory> = emptySet(),
    val olderMillis: Long? = null,
    val world: String? = null,
    val player: String? = null,
    val confirmed: Boolean = false,
    val errors: List<Component> = emptyList(),
) {
    val isEmpty: Boolean
        get() = !everything && categories.isEmpty() && olderMillis == null && world == null && player == null
}
