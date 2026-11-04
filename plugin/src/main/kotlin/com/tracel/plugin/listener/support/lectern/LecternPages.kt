package com.tracel.plugin.listener.support.lectern

import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.concurrent.ExpiringMap
import com.tracel.plugin.util.units.MILLIS_PER_DAY

/**
 * Storage for lectern page numbers.
 *
 * Remembers the page when a book is removed, so it can be restored if the book
 * is placed back during rollback.
 *
 * Stored in memory only.
 */
internal object LecternPages {
    private const val TTL_MS = MILLIS_PER_DAY
    private const val MAX_REMEMBERED = 16_384

    private val byLectern = ExpiringMap<BlockPos, Int>(TTL_MS, MAX_REMEMBERED)

    fun left(at: BlockPos, page: Int) {
        if (page > 0) byLectern.put(at, page) else byLectern.remove(at)
    }

    fun take(at: BlockPos): Int? = byLectern.remove(at)
}
