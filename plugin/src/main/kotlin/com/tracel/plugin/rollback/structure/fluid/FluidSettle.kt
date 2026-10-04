package com.tracel.plugin.rollback.structure.fluid

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.rollback.structure.block.isAir
import com.tracel.plugin.util.LongHashSet
import com.tracel.plugin.util.packed
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Waterlogged

// TODO: rewrite

private const val FEEDS = 1
private const val TOUCHES = 2

@Unstable
/** Settle fluids after a rollback, so that they flow into the new empty spaces. */
internal suspend fun settleFluids(
    world: World,
    steps: List<StructureStep.SetBlock>,
    owns: (Int, Int) -> Boolean,
    pace: suspend () -> Unit = {},
) {
    if (steps.isEmpty()) return
    val stirring = steps.filter { touchesOrFeeds(it.target) || touchesOrFeeds(it.expected) }
    if (stirring.isEmpty()) return
    val ticked = LongHashSet(stirring.size * 7)

    val emptied = LongHashSet(steps.size)
    for ((at, target) in steps) {
        if (target.isAir()) emptied += packed(at.x, at.y, at.z)
    }

    for ((at, target, expected) in stirring) {
        pace()
        val flags = flagsOf(target) or flagsOf(expected)
        if (flags and FEEDS != 0) {
            if (owns(at.x, at.z)) runCatching { world.getBlockAt(at.x, at.y, at.z).tick() }
        }
        if (flags and TOUCHES == 0) continue
        if (packed(at.x, at.y, at.z) in emptied) continue
        tickFluidAt(world, at.x, at.y, at.z, owns, ticked, emptied)
        for (face in CARDINAL) {
            tickFluidAt(world, at.x + face.modX, at.y + face.modY, at.z + face.modZ, owns, ticked, emptied)
        }
    }
}

internal fun tickFluidAt(
    world: World,
    x: Int,
    y: Int,
    z: Int,
    owns: (Int, Int) -> Boolean,
    ticked: LongHashSet,
    emptied: LongHashSet,
) {
    if (!owns(x, z)) return
    for (face in CARDINAL) {
        if (packed(x + face.modX, y + face.modY, z + face.modZ) in emptied) return
    }
    if (!ticked.add(packed(x, y, z))) return
    val block = world.getBlockAt(x, y, z)
    if (!block.holdsFreeFluid()) return
    runCatching { block.fluidTick() }
}

private val flagCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

internal fun touchesOrFeeds(shape: BlockShape): Boolean = flagsOf(shape) != 0

internal fun touchesFluidCached(shape: BlockShape): Boolean = flagsOf(shape) and TOUCHES != 0

private fun flagsOf(shape: BlockShape): Int = flagCache.getOrPut(shape.data.value) {
    (if (shape.feedsBubbles()) FEEDS else 0) or (if (shape.touchesFluid()) TOUCHES else 0)
}

internal fun BlockShape.touchesFluid(): Boolean {
    if (isFluidShape(this)) return true
    val data = BlockDataCache.of(data) ?: return true
    return data is Waterlogged
}

private fun BlockShape.feedsBubbles(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return false
    return material == Material.SOUL_SAND || material == Material.MAGMA_BLOCK
}
