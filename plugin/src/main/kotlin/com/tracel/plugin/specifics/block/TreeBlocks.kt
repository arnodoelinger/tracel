package com.tracel.plugin.specifics.block

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves

/** How far from a log leaves survive. At this distance they decay. */
internal const val LEAF_MAX_DISTANCE: Int = 7

/** Whether this material is a log leaves count their distance from. */
internal fun Material.isLog(): Boolean = Tag.LOGS.isTagged(this)

/** Whether [data] is part of a tree: a log or leaves. */
internal fun isTreePart(data: BlockData): Boolean = data.material.isLog() || data is Leaves
