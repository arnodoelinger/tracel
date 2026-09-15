package com.tracel.plugin.rollback.structure

import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.adapter.entity.applyLeash
import com.tracel.plugin.adapter.entity.applyVehicle
import com.tracel.plugin.adapter.entity.spawnInto
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.entity.toShape
import com.tracel.plugin.listener.support.FluidDisturbance
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.block.Applied
import com.tracel.plugin.rollback.structure.block.Refused
import com.tracel.plugin.rollback.structure.block.apply
import com.tracel.plugin.rollback.structure.block.blockAt
import com.tracel.plugin.rollback.structure.block.chunksOf
import com.tracel.plugin.rollback.structure.block.hasGravity
import com.tracel.plugin.rollback.structure.block.isAir
import com.tracel.plugin.rollback.structure.block.isFire
import com.tracel.plugin.rollback.structure.block.loadChunks
import com.tracel.plugin.rollback.structure.block.overlappingFalling
import com.tracel.plugin.rollback.structure.block.standsAlone
import com.tracel.plugin.rollback.structure.entity.Despawn
import com.tracel.plugin.rollback.structure.entity.despawn
import com.tracel.plugin.rollback.structure.fluid.MAX_DRAINED
import com.tracel.plugin.rollback.structure.fluid.drainFlowing
import com.tracel.plugin.rollback.structure.fluid.fixSnowyGround
import com.tracel.plugin.rollback.structure.fluid.settleFluids
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.Warnings
import java.util.UUID
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity

/** Other region's spawn should be done. */
private const val REATTACH_DELAY_TICKS = 5L

/** Apply group structure. */
internal fun StructureRestorer.applyGroup(
    world: World,
    steps: List<StructureStep>,
    force: Boolean,
    trace: RollbackTrace, // TODO: remove me
    structurePhase: StructurePhase,
    drain: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
): StructureReport {
    val skipped = mutableListOf<SkippedStep>()
    val applied = mutableListOf<StructureStep>()
    var overwritten = 0
    var blockEntities = 0

    // Folia already owns this region
    val phase = structurePhase.traceName
    val chunks = chunksOf(steps)
    trace.measure("$phase / load chunks") { loadChunks(world, chunks) }

    val blocks = steps.filterIsInstance<StructureStep.SetBlock>()
    val removals = steps.filterIsInstance<StructureStep.RemoveEntity>()

    // Spawn leash / vehicle anchors before hangers
    val unordered = steps.filterIsInstance<StructureStep.SpawnEntity>()
    val anchors = HashSet<UUID>()
    for (step in unordered) {
        step.shape.extras.leashHolder?.let(anchors::add)
        step.shape.extras.vehicle?.let(anchors::add)
    }
    val spawns = if (anchors.isEmpty()) unordered else unordered.sortedBy { if (it.entity in anchors) 0 else 1 }

    // Seed fluid disturbance before any write. Drain also touches fluids; a few hundred blocks
    // later water is already moving. Follow the water.
    val wetted = blocks.filter { isFluidShape(it.target) || isFluidShape(it.expected) }
    if (wetted.isNotEmpty()) FluidDisturbance.disturb(wetted.map { it.at })

    // Despawn first
    val gone = HashSet<UUID>(removals.size)
    trace.measure("$phase / despawn") {
        for (step in removals) {
            when (val outcome = despawn(world, step, ledgerCargoFor, ledgerHeldBy)) {
                is Despawn.Removed -> {
                    gone += outcome.uuid
                    applied += step
                }
                // Already gone; not an undo target
                Despawn.Absent -> Unit
                is Despawn.Refused -> skipped += SkippedStep(step.at, outcome.reason)
            }
        }
        if (blocks.any { it.target.hasGravity() || it.expected.hasGravity() }) {
            for (falling in overlappingFalling(world, blocks)) {
                if (!gone.add(falling.uniqueId)) continue
                falling.dropItem = false

                // Paper still drops unless cancel is set too
                runCatching { falling.cancelDrop = true }
                applied += StructureStep.RemoveEntity(falling.toBlockPos(), falling.uniqueId, falling.toShape())
                falling.remove()
            }
        }
    }

    // Order: standalone, then attached, then gravity
    val (standalone, rest) = blocks.partition { it.target.standsAlone() }
    val (gravity, attached) = rest.partition { it.target.hasGravity() }

    trace.measure("$phase / set blocks") {
        for (step in standalone + attached + gravity) {
            val block = world.blockAt(step.at)
            when (val outcome = apply(block, step, force, dumpHeldCargo)) {
                is Applied -> {
                    applied += outcome.step
                    if (outcome.differed) overwritten++
                    if (outcome.step.expected.extras != null) blockEntities++
                }
                is Refused -> {
                    skipped += SkippedStep(step.at, outcome.reason)
                }
            }
        }
        for ((at, target) in standalone + attached + gravity) {
            val block = world.blockAt(at)
            if (block.isFire() && !target.isFire()) {
                target.applyTo(block, physics = false)
            }
            if (!target.isAir()) {
                val above = block.getRelative(BlockFace.UP)
                if (above.isFire()) above.type = Material.AIR
            }
        }
    }
    trace.add("${structurePhase.label} with nbt", blockEntities)

    // Second falling sweep: first ran before writes; a gravel wall takes long enough that the
    // world outside (physics is off (!) here) can drop more onto it.
    if (blocks.any { it.target.hasGravity() || it.expected.hasGravity() }) {
        trace.measure("$phase / late falling") {
            for (falling in overlappingFalling(world, blocks)) {
                if (!gone.add(falling.uniqueId)) continue
                falling.dropItem = false
                runCatching { falling.cancelDrop = true }
                applied += StructureStep.RemoveEntity(falling.toBlockPos(), falling.uniqueId, falling.toShape())
                falling.remove()
            }
        }
    }

    trace.measure("$phase / snowy ground") { fixSnowyGround(world, blocks, chunks) }
    if (drain) {
        trace.measure("$phase / drain liquid") {
            if (!drainFlowing(world, blocks, chunks) && blocks.isNotEmpty()) {
                skipped += SkippedStep(blocks.first().at, "the fluid drain hit its $MAX_DRAINED cell limit")
            }
        }
    }
    trace.measure("$phase / settle liquid") { settleFluids(world, blocks, chunks) }

    val looseEnds = mutableListOf<Pair<Entity, EntityShape>>()
    trace.measure("$phase / spawn") {
        for (step in spawns) {
            // Do not remove() a living hull before respawn
            val inPlace = step.expected != null
            val hull = step.shape.spawnInto(world, step.entity, step.entity in keepCargoFor, resurrect = !inPlace)
            if (hull != null) {
                applied += step
                if (!hull.linkedAsRecorded(step.shape)) looseEnds += hull to step.shape
            } else {
                val why = if (inPlace) "entity is no longer here — a change to it is not a resurrection" else "entity could not be restored"
                skipped += SkippedStep(step.at, why)
            }
        }
    }
    // Other region's knot / boat may not exist yet. One delayed retry
    for ((hull, shape) in looseEnds) reattachLater(hull, shape)

    return StructureReport(applied, skipped, overwritten)
}

private fun Entity.linkedAsRecorded(shape: EntityShape): Boolean {
    val tied = applyLeash(shape.extras.leashHolder)
    val seated = applyVehicle(shape.extras.vehicle)
    return tied && seated
}

private fun StructureRestorer.reattachLater(
    hull: Entity,
    shape: EntityShape,
) {
    runCatching {
        hull.scheduler.runDelayed(services.plugin, {
            if (!hull.isValid || hull.linkedAsRecorded(shape)) return@runDelayed

            // Do not cut a working lead because a seat is still missing
            Warnings.once(logger, "link:${hull.type}") {
                val holder = shape.extras.leashHolder ?: shape.extras.vehicle
                "restored a ${hull.type.name.lowercase()} whose leash holder or vehicle " +
                    "($holder) was not put back in time — it keeps whatever link it has"
            }
        }, {}, REATTACH_DELAY_TICKS)
    }
}
