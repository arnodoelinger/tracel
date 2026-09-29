package com.tracel.plugin.rollback.structure

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.isFluidShape
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.entity.*
import com.tracel.plugin.listener.support.cell.FluidCell
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.block.*
import com.tracel.plugin.rollback.structure.entity.Despawn
import com.tracel.plugin.rollback.structure.entity.despawn
import com.tracel.plugin.rollback.structure.fluid.fixSnowyGround
import com.tracel.plugin.util.Warnings
import com.tracel.plugin.util.chunkKey
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import org.bukkit.entity.Entity
import java.util.*

/** Other region's spawn should be done. */
private const val REATTACH_DELAY_TICKS = 5L

/** Apply group structure. */
@Unstable
internal fun StructureRestorer.applyGroup(
    world: World,
    steps: List<StructureStep>,
    force: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
    driftOnly: Boolean = false,
): StructureReport {
    val skipped = mutableListOf<SkippedStep>()
    val applied = mutableListOf<StructureStep>()
    var overwritten = 0
    var blockEntities = 0

    // Folia already owns this region
    val chunks = steps.mapTo(HashSet()) { dispatchAt(it).let { at -> chunkKey(at.x, at.z) } }
    loadChunks(world, chunks)

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
    if (wetted.isNotEmpty()) FluidCell.disturb(wetted.map { it.at })

    // Despawn first
    val gone = HashSet<UUID>(removals.size)
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

    // Order: standalone, then attached, then gravity
    val hangings = if (blocks.none { it.target.isSolid() }) emptySet() else hangingCells(world, chunks, gone)
    val (blocked, placeable) = blocks.partition { it.at in hangings && it.target.isSolid() }
    for ((at) in blocked) skipped += SkippedStep(at, "a painting or item frame hangs in this cell")
    val (standalone, rest) = placeable.partition { it.target.standsAlone() }
    val (gravity, attached) = rest.partition { it.target.hasGravity() }

    val paste = PalettePaste.tryOpen(world)
    paste?.bind()
    try {
        fun write(step: StructureStep.SetBlock) {
            val outcome = paste?.place(step, force, driftOnly) ?: run {
                val block = world.blockAt(step.at)
                val forced = force && (!driftOnly || block.drifted(step.expected))
                apply(block, step, forced, dumpHeldCargo)
            }
            when (outcome) {
                is Applied -> {
                    applied += outcome.step
                    if (outcome.differed) overwritten++
                    if (outcome.step.expected.extras != null) blockEntities++
                }

                is Refused -> {
                    skipped += SkippedStep(step.at, outcome.reason)
                }

                Unchanged -> Unit
            }
        }

        val (hanging, held) = attached.partition { !it.target.unsupportedAt(world.blockAt(it.at)) }
        for (step in standalone + hanging + gravity) write(step)

        fun sweep(steps: List<StructureStep.SetBlock>): List<StructureStep.SetBlock> {
            val left = ArrayList<StructureStep.SetBlock>()
            for (step in steps) if (step.target.unsupportedAt(world.blockAt(step.at))) left += step else write(step)
            return left
        }

        var waiting = sweep(held.sortedBy { it.at.y })
        if (waiting.isNotEmpty()) waiting = sweep(waiting.sortedByDescending { it.at.y })
        while (waiting.isNotEmpty()) {
            val (ready, still) = waiting.partition { !it.target.unsupportedAt(world.blockAt(it.at)) }
            if (ready.isEmpty()) break
            ready.forEach(::write)
            waiting = still
        }
        for ((at) in waiting) skipped += SkippedStep(at, UNSUPPORTED)
        val unwritten = waiting.mapTo(HashSet()) { it.at }

        val planned = blocks.associateBy { it.at }
        for ((at, target) in standalone + attached + gravity) {
            if (at in unwritten || target.isAir()) continue
            if (paste != null) {
                extinguish(paste, world, at, target, planned, unwritten, applied)
            } else {
                val block = world.blockAt(at)
                if (block.isFire() && !target.isFire()) {
                    val data = BlockDataCache.of(target.data)
                    if (data != null && target.extras == null) block.paint(data) else target.applyTo(
                        block,
                        physics = false
                    )
                }
                if (!target.isFire()) {
                    val above = block.getRelative(BlockFace.UP)
                    val abovePos = at.copy(y = at.y + 1)
                    if (above.isFire() && planned[abovePos]?.target?.isFire() != true && abovePos !in unwritten) {
                        val burning = above.toShape()
                        above.paint(airBlockData())
                        applied += StructureStep.SetBlock(abovePos, BlockShape.AIR, burning)
                    }
                }
            }
        }
        unpairOrphanedChests(world, applied)

        // Second falling sweep: first ran before writes; a gravel wall takes long enough that the
        // world outside (physics is off (!) here) can drop more onto it.
        if (blocks.any { it.target.hasGravity() || it.expected.hasGravity() }) {
            for (falling in overlappingFalling(world, blocks)) {
                if (!gone.add(falling.uniqueId)) continue
                falling.dropItem = false
                runCatching { falling.cancelDrop = true }
                applied += StructureStep.RemoveEntity(falling.toBlockPos(), falling.uniqueId, falling.toShape())
                falling.remove()
            }
        }

        fixSnowyGround(world, blocks, chunks)
    } finally {
        val blocksWritten = paste?.written ?: 0
        paste?.close()
    }

    val looseEnds = mutableListOf<Pair<Entity, EntityShape>>()
    for (step in spawns) {
        // Do not remove() a living hull before respawn
        val inPlace = step.expected != null
        val before = if (inPlace) null else Bukkit.getEntity(step.entity)?.takeIf { it.isValid }
        val hull = step.shape.spawnInto(world, step.entity, step.entity in keepCargoFor, resurrect = !inPlace)
        if (hull != null) {
            if (hull !== before) applied += step
            services.whereabouts.remember(hull)
            if (!hull.linkedAsRecorded(step.shape)) looseEnds += hull to step.shape
        } else {
            val why =
                if (inPlace) "entity is no longer here — a change to it is not a resurrection" else "entity could not be restored"
            skipped += SkippedStep(step.at, why)
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

/** Fire left on or above a restored block. Reads the section directly and only opens a Bukkit block when it is fire. */
private fun extinguish(
    paste: PalettePaste,
    world: World,
    at: BlockPos,
    target: BlockShape,
    planned: Map<BlockPos, StructureStep.SetBlock>,
    unwritten: Set<BlockPos>,
    applied: MutableList<StructureStep>,
) {
    if (!target.isFire() && paste.fireAt(world, at.x, at.y, at.z)) {
        val block = world.blockAt(at)
        val data = BlockDataCache.of(target.data)
        if (data != null && target.extras == null) block.paint(data) else target.applyTo(block, physics = false)
    }
    if (target.isFire()) return
    val above = at.copy(y = at.y + 1)
    if (planned[above]?.target?.isFire() == true || above in unwritten) return
    if (!paste.fireAt(world, above.x, above.y, above.z)) return
    val block = world.getBlockAt(above.x, above.y, above.z)
    val burning = block.toShape()
    block.paint(airBlockData())
    applied += StructureStep.SetBlock(above, BlockShape.AIR, burning)
}

private fun PalettePaste.fireAt(world: World, x: Int, y: Int, z: Int): Boolean {
    val state = read(x, y, z)
    if (state == null) return world.getBlockAt(x, y, z).isFire()
    val material = material(state)
    return material == Material.FIRE || material == Material.SOUL_FIRE
}

private fun unpairOrphanedChests(world: World, applied: List<StructureStep>) {
    for (step in applied) {
        if (step !is StructureStep.SetBlock) continue
        val was = BlockDataCache.of(step.expected.data) as? Chest ?: continue
        if (was.type == Chest.Type.SINGLE || BlockDataCache.of(step.target.data) is Chest) continue
        val towards = if (was.type == Chest.Type.LEFT) was.facing.clockwise() else was.facing.counterClockwise()
        val partner = world.getBlockAt(step.at.x + towards.modX, step.at.y, step.at.z + towards.modZ)
        val data = partner.blockData as? Chest ?: continue
        if (data.type == Chest.Type.SINGLE || data.facing != was.facing) continue
        data.type = Chest.Type.SINGLE
        partner.paint(data)
    }
}

private fun BlockFace.clockwise(): BlockFace = when (this) {
    BlockFace.NORTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.WEST
    else -> BlockFace.NORTH
}

private fun BlockFace.counterClockwise(): BlockFace = when (this) {
    BlockFace.NORTH -> BlockFace.WEST
    BlockFace.WEST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.EAST
    else -> BlockFace.NORTH
}
