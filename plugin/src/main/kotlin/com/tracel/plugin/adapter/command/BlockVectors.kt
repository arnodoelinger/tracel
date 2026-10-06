package com.tracel.plugin.adapter.command

import com.tracel.plugin.util.command.ParsedBlockPos
import org.bukkit.util.Vector

/** This block coordinate as a `Bukkit` vector. */
internal fun ParsedBlockPos.vector(): Vector = Vector(x.toDouble(), y.toDouble(), z.toDouble())
