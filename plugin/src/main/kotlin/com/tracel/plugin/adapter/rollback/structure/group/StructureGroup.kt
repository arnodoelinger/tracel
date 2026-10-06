package com.tracel.plugin.adapter.rollback.structure.group

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.block.BlockDataCache
import com.tracel.plugin.adapter.block.applyTo
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.entity.remember
import com.tracel.plugin.adapter.entity.spawnInto
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.entity.toShape
import com.tracel.plugin.adapter.rollback.structure.block.*
import com.tracel.plugin.adapter.rollback.structure.block.check.*
import com.tracel.plugin.adapter.rollback.structure.block.write.apply
import com.tracel.plugin.adapter.rollback.structure.block.write.place
import com.tracel.plugin.adapter.rollback.structure.entity.despawn
import com.tracel.plugin.adapter.rollback.structure.fluid.fixSnowyGround
import com.tracel.plugin.governor.Throttle
import com.tracel.plugin.rollback.result.report.SkippedStep
import com.tracel.plugin.rollback.result.report.StructureReport
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.block.write.Applied
import com.tracel.plugin.rollback.structure.block.write.Refused
import com.tracel.plugin.rollback.structure.block.write.Unchanged
import com.tracel.plugin.rollback.structure.dispatchAt
import com.tracel.plugin.rollback.structure.entity.Despawn
import com.tracel.plugin.specifics.block.AIR
import com.tracel.plugin.util.geometry.chunkKey
import com.tracel.plugin.util.geometry.chunkKeyX
import com.tracel.plugin.util.geometry.chunkKeyZ
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import java.util.*

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
                        applied += StructureStep.SetBlock(abovePos, AIR, burning)
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
