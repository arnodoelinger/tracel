package com.tracel.plugin.rollback.structure.fluid

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.util.collection.LongHashSet
import com.tracel.plugin.util.geometry.packed
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * Holds the world still while a rollback job is planning and applying, so what the log says when the plan is made is
 * what the job will write.
 */
internal class FluidFreeze {
    private sealed interface Held {
        fun holds(world: WorldId, x: Int, y: Int, z: Int, key: Long): Boolean
    }

    private class Cells(val world: WorldId, val cells: LongHashSet) : Held {
        override fun holds(world: WorldId, x: Int, y: Int, z: Int, key: Long) = this.world == world && key in cells

        fun has(world: WorldId, x: Int, y: Int, z: Int) = this.world == world && packed(x, y, z) in cells
    }

    private class Area(val region: LookupRegion) : Held {
        override fun holds(world: WorldId, x: Int, y: Int, z: Int, key: Long) =
            region.world == world && region.containsBlock(x, y, z)
    }

    private class Hold(
        val parts: List<Held>,
        val wake: suspend (List<BlockPos>) -> Unit,
    ) : AbstractCoroutineContextElement(Key) {
        val written = ArrayList<Cells>()
        var released = false
    }

    private object Key : CoroutineContext.Key<Hold>

    @Volatile
    private var held: List<Held> = emptyList()
    private val lock = Any()

    private val stirred = ConcurrentHashMap.newKeySet<BlockPos>()

    private companion object {
        val NEIGHBORS = intArrayOf(1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1, 0, 0, 0, 1, 0, 0, -1)
    }

    /** Whether a running job holds the cell. Any thread; free when nothing is held. */
    fun holds(world: WorldId, x: Int, y: Int, z: Int): Boolean {
        val now = held
        if (now.isEmpty()) return false
        val key = packed(x, y, z)
        for (hold in now) if (hold.holds(world, x, y, z, key)) return true
        return false
    }

    /**
     * The fluid at this cell was about to move and the freeze stopped it. A stopped fluid does not ask again by
     * itself, so it is handed to whoever lets go last, to be ticked.
     */
    fun stir(world: WorldId, x: Int, y: Int, z: Int) {
        stirred += BlockPos(world, x, y, z)
    }

    /** Holds every cell [steps] write until [work] returns, then [wake]s what it stopped. */
    suspend fun <T> holding(
        steps: List<StructureStep>,
        wake: suspend (List<BlockPos>) -> Unit,
        work: suspend () -> T,
    ): T {
        val cells = cellsOf(steps)
        if (cells.isEmpty()) return work()
        currentCoroutineContext()[Key]?.let { area -> synchronized(lock) { area.written += cells } }
        return hold(Hold(cells, wake), work)
    }

    /**
     * Holds everything in [region] until [work] returns or it lets go ([letGoOfArea]).
     *
     * No region holds nothing.
     */
    suspend fun <T> holdingArea(
        region: LookupRegion?,
        wake: suspend (List<BlockPos>) -> Unit,
        work: suspend () -> T,
    ): T {
        if (region == null) return work()
        val area = Hold(listOf(Area(region)), wake)
        return hold(area) { withContext(area) { work() } }
    }

    /** Lets go of the area the calling rollback holds, once its last write is in: what follows is the world's. */
    suspend fun letGoOfArea() {
        currentCoroutineContext()[Key]?.let { release(it) }
    }

    private suspend fun <T> hold(hold: Hold, work: suspend () -> T): T {
        synchronized(lock) { held = held + hold.parts }
        try {
            return work()
        } finally {
            release(hold)
        }
    }

    private suspend fun release(hold: Hold) {
        val loose = synchronized(lock) {
            if (hold.released) return
            hold.released = true
            held = held - hold.parts.toSet()
            val free = stirred.filter { !holds(it.world, it.x, it.y, it.z) }
            stirred.removeAll(free.toSet())
            free.filter { hold.keeps(it) }
        }
        if (loose.isNotEmpty()) withContext(NonCancellable) { hold.wake(loose) }
    }

    private fun Hold.keeps(pos: BlockPos): Boolean {
        val mine = written + parts.filterIsInstance<Cells>()
        if (mine.any { it.has(pos.world, pos.x, pos.y, pos.z) }) return false
        if (written.isEmpty()) return true
        if (parts.none { it is Area && it.region.contains(pos) }) return true
        for (i in 0 until 6) {
            val x = pos.x + NEIGHBORS[i * 3]
            val y = pos.y + NEIGHBORS[i * 3 + 1]
            val z = pos.z + NEIGHBORS[i * 3 + 2]
            if (written.any { it.has(pos.world, x, y, z) }) return false
        }
        return true
    }

    private fun cellsOf(steps: List<StructureStep>): List<Cells> {
        val byWorld = HashMap<WorldId, LongHashSet>()
        for (step in steps) {
            if (step !is StructureStep.SetBlock) continue
            val at = step.at
            byWorld.getOrPut(at.world) { LongHashSet(steps.size) } += packed(at.x, at.y, at.z)
        }
        return byWorld.map { (world, cells) -> Cells(world, cells) }
    }
}
