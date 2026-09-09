package com.tracel.engine.container

import com.tracel.model.holder.HolderId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory [ContainerSlotLog]. */
public class InMemoryContainerSlotLog : ContainerSlotLog {
    private val seq = AtomicLong(0)
    private val byHolder = ConcurrentHashMap<HolderId.Block, ConcurrentSkipListMap<Long, Entry>>()

    override suspend fun record(holder: HolderId.Block, epochMillis: Long, slots: List<ContainerSlotEntry>) {
        val column = byHolder.getOrPut(holder) { ConcurrentSkipListMap() }
        column[descKey(seq.incrementAndGet())] = Entry(epochMillis, slots)
    }

    override suspend fun layoutAt(holder: HolderId.Block, asOfMillis: Long, limit: Int): List<ContainerSlotEntry>? {
        if (limit <= 0) return null
        val column = byHolder[holder] ?: return null
        var seen = 0
        for ((epochMillis, slots) in column.values) {
            if (epochMillis <= asOfMillis) return slots
            if (++seen >= limit) return null
        }
        return null
    }

    private data class Entry(val epochMillis: Long, val slots: List<ContainerSlotEntry>)

    private companion object {
        fun descKey(seq: Long): Long = Long.MAX_VALUE - seq
    }
}
