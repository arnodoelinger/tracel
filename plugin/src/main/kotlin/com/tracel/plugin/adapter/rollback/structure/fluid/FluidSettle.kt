package com.tracel.plugin.adapter.rollback.structure.fluid

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.specifics.block.holdsFreeFluid
import com.tracel.plugin.specifics.block.makesBubbles
import com.tracel.plugin.specifics.block.poursLikeFluid
import com.tracel.plugin.util.collection.LongHashSet
import com.tracel.plugin.util.geometry.packed
import org.bukkit.World
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.Waterlogged

private const val FEEDS = 1
private const val TOUCHES = 2
private const val FLOWING = 4

/**
 * One tick for the fluids among [steps] and against them outside [footprint], like the neighbor update the write
 * skipped. A bubble column base written gets its block tick, which rebuilds the column above it.
 */
internal suspend fun wakeFluids(
    world: World,
    steps: List<StructureStep.SetBlock>,
    footprint: LongHashSet,
    asItStood: LookupRegion?,
    owns: (Int, Int) -> Boolean,
    pace: suspend () -> Unit = {},
) {
    val woken = LongHashSet(steps.size)
    val stood = asItStood?.takeIf { it.world == WorldId(world.uid) }
    for ((at, target) in steps) {
        pace()
        val x = at.x
        val y = at.y
        val z = at.z
        if (!owns(x, z)) continue
        val flags = flagsOf(target)
        if (flags and FEEDS != 0) runCatching { world.getBlockAt(x, y, z).tick() }
        if (flags and TOUCHES != 0 && (stood == null || flags and FLOWING != 0)) wake(world, x, y, z, woken)
        for (face in CARDINAL) {
            val nx = x + face.modX
            val ny = y + face.modY
            val nz = z + face.modZ
            if (packed(nx, ny, nz) in footprint || !owns(nx, nz)) continue
            if (stood != null && stood.containsBlock(nx, ny, nz)) continue
            wake(world, nx, ny, nz, woken)
        }
    }
}

private fun wake(world: World, x: Int, y: Int, z: Int, woken: LongHashSet) {
    if (!woken.add(packed(x, y, z))) return
    val block = world.getBlockAt(x, y, z)
    if (!block.holdsFreeFluid()) return
    runCatching { block.fluidTick() }
}

private val flagCache = java.util.concurrent.ConcurrentHashMap<String, Int>()

private fun flagsOf(shape: BlockShape): Int = flagCache.getOrPut(shape.data.value) {
    (if (shape.feedsBubbles()) FEEDS else 0) or (if (shape.touchesFluid()) TOUCHES else 0) or
            (if (shape.flowing()) FLOWING else 0)
}

private fun BlockShape.flowing(): Boolean {
    val data = BlockDataCache.of(data) as? Levelled ?: return false
    return data.material.poursLikeFluid() && data.level != 0
}

private fun BlockShape.touchesFluid(): Boolean {
    if (isFluidShape(this)) return true
    val data = BlockDataCache.of(data) ?: return true
    return data is Waterlogged
}

private fun BlockShape.feedsBubbles(): Boolean {
    val material = BlockDataCache.of(data)?.material ?: return false
    return material.makesBubbles()
}
