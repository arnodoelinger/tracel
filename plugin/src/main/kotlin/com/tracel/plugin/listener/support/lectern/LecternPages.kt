package com.tracel.plugin.listener.support.lectern

import com.tracel.engine.world.BlockNotes
import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.concurrent.DiskMirror

/** The page a lectern's book lay open at when it left. */
internal object LecternPages {
    private val byLectern = DiskMirror<BlockPos, Int>()

    /** Starts from what [store] holds. Before any listener runs. */
    fun open(store: BlockNotes) {
        byLectern.open(store.loadAll(), store::writeAll)
    }

    /**
     * Left a lectern at [at] with its book open at [page].
     *
     * If [page] is 0, the lectern is no longer tracked.
     */
    fun left(at: BlockPos, page: Int) {
        if (page > 0) byLectern.put(at, page) else byLectern.remove(at)
    }

    /**
     * @return the page a lectern's book was open at when it left,
     * or `null` if it is not tracked.
     */
    fun take(at: BlockPos): Int? = byLectern.remove(at)

    suspend fun flush() = byLectern.flush()

    /** Forget all lecterns and their pages. */
    fun forgetAll() = byLectern.forgetAll()
}
