package com.tracel.plugin.listener.support

import com.tracel.model.world.BlockPos
import com.tracel.plugin.util.ExpiringMap

/**
 * Storage for lectern page numbers.
 *
 * Remembers the page when a book is removed, so it can be restored if the book
 * is placed back during rollback.
 *
 * Stored in memory only.
 */
internal object LecternPages {
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_REMEMBERED = 16_384

    private val byLectern = ExpiringMap<BlockPos, Int>(TTL_MS, MAX_REMEMBERED)

    fun left(at: BlockPos, page: Int) {
        if (page > 0) byLectern.put(at, page) else byLectern.remove(at)
    }

    fun take(at: BlockPos): Int? = byLectern.remove(at)
}
