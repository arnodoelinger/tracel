package com.tracel.plugin.rollback.structure.block

import com.tracel.plugin.util.isAirLike
import com.tracel.plugin.util.AIR
import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.BlockLikeness
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.data.type.Leaves

/**
 * Plain-block half of a rollback, written through [PalettePaste].
 *
 * Tiles, captured worlds and anything the paste cannot see fall through as `null` so the `Bukkit`
 * path still runs.
 *
 * A write that already landed never falls through. `Bukkit` would treat it as
 * unchanged and drop it from the report.
 */
@Unstable
internal fun PalettePaste.place(step: StructureStep.SetBlock, force: Boolean, driftOnly: Boolean): Outcome? {
    if (capturing()) return null
    if (step.target.extras != null || step.expected.extras != null) return null
    val target = PasteShapes.of(step.target)
    if (target.movingPiston) return Refused("was caught mid-push by a piston; nothing to put back")
    if (target.data == null) return null
    val targetState = target.stateIn(this) ?: return null
    val expected = PasteShapes.of(step.expected)
    val expectedAir = expected.air || (expected.data == null && step.expected == AIR)
    val expectedState = if (expected.data != null && !expected.air) (expected.stateIn(this) ?: return null) else null
    when (
        placeFast(
            step.at.x,
            step.at.y,
            step.at.z,
            targetState,
            expectedState,
            target.air,
            expectedAir,
        )
    ) {
        PalettePaste.Fast.UNCHANGED -> return Unchanged
        PalettePaste.Fast.WRITTEN -> {
            wakeIfNeeded(step.at.x, step.at.y, step.at.z, target)
            return Applied(step)
        }

        PalettePaste.Fast.BUKKIT -> return null
        PalettePaste.Fast.DRIFTED -> Unit
    }
    val pending = try {
        plan(step, force, driftOnly)
    } catch (_: Throwable) {
        return null
    }
    return when (pending) {
        null -> null
        is Ready -> pending.outcome
        is Pending -> {
            if (!commit(pending.x, pending.y, pending.z, pending.live, pending.state)) return null
            runCatching { wake(pending) }
            pending.outcome
        }
    }
}

private fun PalettePaste.wakeIfNeeded(x: Int, y: Int, z: Int, target: PasteShape) {
    val live = lastRead
    val bubblesWere = live != null && wakesBubbles(material(live))
    if (!target.leafy && !target.bubbly && !bubblesWere) return
    val block = world.getBlockAt(x, y, z)
    if (target.bubbly || bubblesWere) block.wakeBubbles()
    if (target.leafy) runCatching { block.settleLeaves() }
}

private fun PalettePaste.plan(step: StructureStep.SetBlock, force: Boolean, driftOnly: Boolean): Plan? {
    val targetData = BlockDataCache.of(step.target.data) ?: return null
    val targetState = stateOf(targetData) ?: return null
    if (hasBlockEntity(targetState)) return null

    val x = step.at.x
    val y = step.at.y
    val z = step.at.z
    val live = read(x, y, z) ?: return null
    if (hasBlockEntity(live)) return null

    val liveAir = isAir(live)
    if (targetData.material.isAir) {
        if (liveAir) return Ready(Unchanged)
    } else if (live === targetState) {
        return Ready(Unchanged)
    }

    val expectedData = BlockDataCache.of(step.expected.data)
    val expectedState = if (expectedData != null && !expectedData.material.isAir) stateOf(expectedData) else null
    if (expectedData != null && !expectedData.material.isAir && expectedState == null) return null

    val matchesExpected = when {
        expectedData == null -> step.expected == AIR && liveAir
        expectedData.material.isAir -> liveAir
        else -> live === expectedState || BlockLikeness.sameEnough(asString(live), step.expected.data.value)
    }
    if (!matchesExpected) {
        val forced = force && (!driftOnly || drifted(live, step.expected))
        if (!forced && (!step.expected.isAirLike || !canReplace(live))) {
            return Ready(Refused("is ${asString(live)}, expected ${step.expected.data.value}"))
        }
    }

    val exact = when {
        expectedData == null -> step.expected.isAirLike && liveAir
        expectedData.material.isAir -> liveAir
        else -> live === expectedState
    }
    val outcome = if (exact) {
        Applied(step)
    } else {
        Applied(step.copy(expected = BlockShape(BlockDataKey(asString(live)))), differed = !matchesExpected)
    }
    return Pending(
        x,
        y,
        z,
        live,
        targetState,
        outcome,
        wakesBubbles(material(live)) || wakesBubbles(targetData.material),
        Tag.LOGS.isTagged(targetData.material) || targetData is Leaves,
    )
}

private fun PalettePaste.wake(pending: Pending) {
    val above = read(pending.x, pending.y + 1, pending.z)
    val bubbleAbove = above != null && material(above) == Material.BUBBLE_COLUMN
    if (pending.bubbles || bubbleAbove) world.getBlockAt(pending.x, pending.y, pending.z).wakeBubbles()
    if (pending.leaves) world.getBlockAt(pending.x, pending.y, pending.z).settleLeaves()
}

private fun PalettePaste.drifted(live: Any, expected: BlockShape): Boolean = drifted(
    material(live),
    liquid(live),
    canReplace(live),
    ageable(live),
    waterlogged(live),
    expected,
)

private sealed interface Plan

private class Ready(val outcome: Outcome) : Plan

private class Pending(
    val x: Int,
    val y: Int,
    val z: Int,
    val live: Any,
    val state: Any,
    val outcome: Outcome,
    val bubbles: Boolean,
    val leaves: Boolean,
) : Plan
