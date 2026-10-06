package com.tracel.plugin.command.presenter.line

import com.tracel.model.world.BlockPos
import net.kyori.adventure.text.Component
import org.bukkit.GameMode

/** A line of the log, block, or item, before the lines that repeat are folded into one. */
internal class LoggedLine(
    val millis: Long,
    val key: Any,
    val mark: Component,
    val who: Component,
    val verb: Component,
    val whoText: String,
    val whatText: (total: Long) -> String,
    val title: String,
    val what: (total: Long) -> Component,
    val quantity: Long = 1,
    val from: Component? = null,
    val to: Component? = null,
    val at: BlockPos? = null,
    val mode: GameMode? = null,
    val net: LineNet? = null,
    val visit: Long? = null,
    val rolledAt: Long? = null,
    val counted: Boolean = true,
    val pairs: Boolean = false,
    val tip: Component? = null,
)
