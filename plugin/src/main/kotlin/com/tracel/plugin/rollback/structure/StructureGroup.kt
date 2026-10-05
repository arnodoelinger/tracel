package com.tracel.plugin.rollback.structure

import com.tracel.plugin.governor.Throttle
import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.entity.*
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.block.*
import com.tracel.plugin.rollback.structure.entity.Despawn
import com.tracel.plugin.rollback.structure.entity.despawn
import com.tracel.plugin.rollback.structure.fluid.fixSnowyGround
import com.tracel.plugin.util.Warnings
import com.tracel.plugin.util.chunkKey
import com.tracel.plugin.util.chunkKeyX
import com.tracel.plugin.util.chunkKeyZ
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Chest
import org.bukkit.entity.Entity
import java.util.*

/** Other region's spawn should be done. */
private const val REATTACH_DELAY_TICKS = 5L

/** Apply group structure, a slice of a tick at a time. */
@Unstable
internal suspend fun StructureRestorer.applyGroup(
    world: World,
    steps: List<StructureStep>,
    force: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
    driftOnly: Boolean = false,
    deferred: MutableCollection<StructureStep> = ArrayList(),
): StructureReport {
    val first = steps.firstOrNull() ?: return StructureReport.EMPTY
    val anchor = dispatchAt(first)
    val throttle = Throttle(services.governor, services.selfManagedWorld, world, anchor.x shr 4, anchor.z shr 4)
    throttle.enter()
    try {
        return applySliced(
            throttle, world, steps, force, keepCargoFor, ledgerCargoFor, ledgerHeldBy, dumpHeldCargo, driftOnly,
            deferred,
        )
    } finally {
        throttle.leave()
    }
}

private suspend fun StructureRestorer.applySliced(
    throttle: Throttle,
    world: World,
    steps: List<StructureStep>,
    force: Boolean,
    keepCargoFor: Set<UUID>,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
    dumpHeldCargo: Boolean,
    driftOnly: Boolean,
    deferred: MutableCollection<StructureStep>,
): StructureReport {
    val skipped = mutableListOf<SkippedStep>()
    var overwritten = 0
    var blockEntities = 0

    // Folia already owns this region.
    // Steps come chunk by chunk, so a key is only boxed when the chunk changes.
    val chunks = HashSet<Long>()
    var lastChunk = Long.MIN_VALUE
    for (step in steps) {
        val at = dispatchAt(step)
        val key = chunkKey(at.x, at.z)
        if (key != lastChunk) {
            chunks += key
            lastChunk = key
        }
    }
    loadChunks(world, chunks)

    val owned = HashMap<Long, Boolean>()

    var lastKey = Long.MIN_VALUE
    var lastOwned = false
    fun ownsKey(key: Long): Boolean {
        if (key == lastKey) return lastOwned
        val answer = owned.getOrPut(key) {
            runCatching { Bukkit.isOwnedByCurrentRegion(world, chunkKeyX(key), chunkKeyZ(key)) }.getOrDefault(false)
        }
        lastKey = key
        lastOwned = answer
        return answer
    }

    fun owns(at: BlockPos): Boolean = ownsKey(chunkKey(at.x, at.z))
    var paste: PalettePaste? = null
    var flushed = 0
    val applied = mutableListOf<StructureStep>()

    suspend fun hop() {
        services.selfManagedWorld.wrote(applied.subList(flushed, applied.size).map { it.at })
        flushed = applied.size
        throttle.nextTick()
        owned.clear()
        lastKey = Long.MIN_VALUE
    }

    suspend fun entityCheckpoint() {
        if (throttle.spent()) hop()
    }

    suspend fun blockCheckpoint() {
        if (!throttle.spent()) return
        paste?.pause()
        hop()
        paste?.resume()
    }

    val blocks = ArrayList<StructureStep.SetBlock>(steps.size)
    val standalone = ArrayList<StructureStep.SetBlock>(steps.size)
    val gravity = ArrayList<StructureStep.SetBlock>()
    val attached = ArrayList<StructureStep.SetBlock>()
    val removals = ArrayList<StructureStep.RemoveEntity>()
    val unordered = ArrayList<StructureStep.SpawnEntity>()
    var anyGravity = false
    var anySolid = false
    var anySnow = false
    for (step in steps) {
        when (step) {
            is StructureStep.SetBlock -> {
                blocks += step
                val target = ShapeTraits.of(step.target)
                val either = target or ShapeTraits.of(step.expected)
                if (either and ShapeTraits.GRAVITY != 0) anyGravity = true
                if (target and ShapeTraits.SOLID != 0) anySolid = true
                if (either and ShapeTraits.SNOW != 0) anySnow = true
                when {
                    target and ShapeTraits.STANDS_ALONE != 0 -> standalone += step
                    target and ShapeTraits.GRAVITY != 0 -> gravity += step
                    else -> attached += step
                }
            }

            is StructureStep.RemoveEntity -> removals += step
            is StructureStep.SpawnEntity -> unordered += step
        }
    }

    // Spawn leash / vehicle anchors before hangers
    val anchors = HashSet<UUID>()
    for (step in unordered) {
        step.shape.extras.leashHolder?.let(anchors::add)
        step.shape.extras.vehicle?.let(anchors::add)
    }
    val spawns = if (anchors.isEmpty()) unordered else unordered.sortedBy { if (it.entity in anchors) 0 else 1 }

    // Despawn first
    val gone = HashSet<UUID>(removals.size)
    for (step in removals) {
        entityCheckpoint()
        if (!owns(dispatchAt(step))) {
            deferred += step
            continue
        }
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
    if (anyGravity) {
        for (falling in overlappingFalling(world, blocks)) {
            if (!gone.add(falling.uniqueId)) continue
            falling.dropItem = false

            // Paper still drops unless cancel is set too
            runCatching { falling.cancelDrop = true }
            applied += StructureStep.RemoveEntity(falling.toBlockPos(), falling.uniqueId, falling.toShape())
            falling.remove()
        }
    }

    val hangings: Set<BlockPos> = if (!anySolid) emptySet() else hangingCells(world, chunks, gone)
    if (hangings.isNotEmpty()) {
        for (list in arrayOf(standalone, gravity, attached)) {
            list.removeAll { step ->
                val blocked = ShapeTraits.of(step.target) and ShapeTraits.SOLID != 0 && step.at in hangings
                if (blocked) skipped += SkippedStep(step.at, "a painting or item frame hangs in this cell")
                blocked
            }
        }
    }

    paste = PalettePaste.tryOpen(world)
    paste?.bind()
    try {
        fun write(step: StructureStep.SetBlock) {
            if (!owns(step.at)) {
                deferred += step
                return
            }
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

        val (hanging, held) = attached.partition { !it.target.unsupportedAt(world, it.at) }
        for (list in arrayOf(standalone, hanging, gravity)) {
            for (step in list) {
                write(step)
                blockCheckpoint()
            }
        }

        suspend fun sweep(steps: List<StructureStep.SetBlock>): List<StructureStep.SetBlock> {
            val left = ArrayList<StructureStep.SetBlock>()
            for (step in steps) {
                if (!owns(step.at)) {
                    deferred += step
                    continue
                }
                if (step.target.unsupportedAt(world, step.at)) left += step else write(step)
                blockCheckpoint()
            }
            return left
        }

        var waiting = sweep(held.sortedBy { it.at.y })
        if (waiting.isNotEmpty()) waiting = sweep(waiting.sortedByDescending { it.at.y })
        while (waiting.isNotEmpty()) {
            val (ready, still) = waiting.partition { owns(it.at) && !it.target.unsupportedAt(world, it.at) }
            if (ready.isEmpty()) break
            for (step in ready) {
                write(step)
                blockCheckpoint()
            }
            waiting = still
        }
        for ((at) in waiting) skipped += SkippedStep(at, UNSUPPORTED)
        val unwritten = waiting.mapTo(HashSet()) { it.at }

        val planned = lazy { blocks.associateBy { it.at } }
        for (list in arrayOf(standalone, attached, gravity)) for ((at, target) in list) {
            blockCheckpoint()
            if (target.isAir() || (unwritten.isNotEmpty() && at in unwritten) || !owns(at)) continue
            val open = paste
            if (open != null) {
                if (open.fireFreeAround(at.x, at.y, at.z)) continue
                extinguish(open, world, at, target, planned, unwritten, applied)
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
                    if (above.isFire() && planned.value[abovePos]?.target?.isFire() != true && abovePos !in unwritten) {
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
        val mineNow = if (anyGravity || anySnow) blocks.filter { owns(it.at) } else emptyList()
        if (anyGravity && mineNow.any { it.target.hasGravity() || it.expected.hasGravity() }) {
            for (falling in overlappingFalling(world, mineNow)) {
                if (!gone.add(falling.uniqueId)) continue
                falling.dropItem = false
                runCatching { falling.cancelDrop = true }
                applied += StructureStep.RemoveEntity(falling.toBlockPos(), falling.uniqueId, falling.toShape())
                falling.remove()
            }
        }

        if (anySnow) fixSnowyGround(world, mineNow, { x, z -> chunkKey(x, z).let { it in chunks && ownsKey(it) } }) {
            if (throttle.spent()) {
                paste?.pause()
                hop()
                paste?.resume()
            }
        }

        paste?.let { open ->
            while (!open.closeSome(throttle.remainingNanos())) {
                open.pause()
                hop()
                open.resume()
            }
        }
    } finally {
        paste?.close()
    }

    val looseEnds = mutableListOf<Pair<Entity, EntityShape>>()
    for (step in spawns) {
        entityCheckpoint()
        if (!owns(step.at)) {
            deferred += step
            continue
        }
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

    // What the last turn wrote; the earlier ones were marked as they went
    services.selfManagedWorld.wrote(applied.subList(flushed, applied.size).map { it.at })
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
    planned: Lazy<Map<BlockPos, StructureStep.SetBlock>>,
    unwritten: Set<BlockPos>,
    applied: MutableList<StructureStep>,
) {
    if (!target.isFire() && paste.fireAt(world, at.x, at.y, at.z)) {
        val block = world.blockAt(at)
        val data = BlockDataCache.of(target.data)
        if (data != null && target.extras == null) block.paint(data) else target.applyTo(block, physics = false)
    }
    if (target.isFire()) return
    if (!paste.fireAt(world, at.x, at.y + 1, at.z)) return
    val above = at.copy(y = at.y + 1)
    if (planned.value[above]?.target?.isFire() == true || above in unwritten) return
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
