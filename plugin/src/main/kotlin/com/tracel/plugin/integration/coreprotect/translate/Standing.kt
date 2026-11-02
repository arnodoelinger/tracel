package com.tracel.plugin.integration.coreprotect.translate

import com.tracel.model.world.block.BlockShape

/** What stands in each cell of one world as the import goes, newest cells kept. */
internal class Standing : LinkedHashMap<Long, BlockShape>(1 shl 12, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, BlockShape>?): Boolean = size > REMEMBERED

    companion object {
        private const val REMEMBERED = 200_000
        private const val LOAD_FACTOR = 0.75f
    }
}
