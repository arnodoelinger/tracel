package com.tracel.plugin.rollback.structure.block

import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import org.bukkit.Material
import org.bukkit.block.Block

/** Solid and not falling; safe to place in any order, needs nothing under or around it first. */
internal fun BlockShape.standsAlone(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return true
    return material.isSolid && !material.hasGravity()
}

/** Falls without support (sand, gravel, concrete powder, ...) — must go down after its support. */
internal fun BlockShape.hasGravity(): Boolean =
    BlockDataCache.of(data)?.material?.hasGravity() == true

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
