package com.tracel.plugin.rollback.structure.block

import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import org.bukkit.Material
import org.bukkit.block.Block

/** Why a block is held back when nothing it hangs on exists. */
internal const val UNSUPPORTED = "nothing is left for it to hang on"

/** Solid and not falling; safe to place in any order, needs nothing under or around it first. */
internal fun BlockShape.standsAlone(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return true
    return material.isSolid && !material.hasGravity()
}

/** Falls without support (sand, gravel, concrete powder, ...) — must go down after its support. */
internal fun BlockShape.hasGravity(): Boolean =
    BlockDataCache.of(data)?.material?.hasGravity() == true

/** Would pop off, drop and all, the first time physics looks at it. */
internal fun BlockShape.unsupportedAt(block: Block): Boolean {
    if (standsAlone() || hasGravity() || isAir()) return false
    val data = BlockDataCache.of(data) ?: return false
    return runCatching { !data.isSupported(block) }.getOrDefault(false)
}

/** Whether a block like this has a body a hanging entity would collide with. */
internal fun BlockShape.isSolid(): Boolean = BlockDataCache.of(data)?.material?.isSolid == true

/** Whether it's a fire. */
internal fun BlockShape.isFire(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return false
    return material == Material.FIRE || material == Material.SOUL_FIRE
}

/** Whether it's an air. */
internal fun BlockShape.isAir(): Boolean =
    this == BlockShape.AIR || BlockDataCache.of(data)?.material?.isAir == true

/** Same check as [BlockShape.isFire], against the block actually in the world. */
internal fun Block.isFire(): Boolean =
    type == Material.FIRE || type == Material.SOUL_FIRE
