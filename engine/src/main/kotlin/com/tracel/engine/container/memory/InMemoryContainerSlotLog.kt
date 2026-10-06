package com.tracel.engine.container.memory

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.engine.container.ContainerSlotLog
import com.tracel.model.holder.HolderId
import com.tracel.platform.concurrency.SingleWriterGuard
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory [ContainerSlotLog], the reference the stored one is checked against. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryContainerSlotLog : ContainerSlotLog {
    private val writer = SingleWriterGuard()
    private val seq = AtomicLong(0)
    private val byHolder = ConcurrentHashMap<HolderId, ConcurrentSkipListMap<Long, Entry>>()

    /** The layout that was recorded at [epochMillis]. */
    private data class Entry(val epochMillis: Long, val slots: List<ContainerSlotEntry>)

    override suspend fun record(holder: HolderId, epochMillis: Long, slots: List<ContainerSlotEntry>) {
        writer.checkIn()
        val column = byHolder.getOrPut(holder) { ConcurrentSkipListMap() }
        column[descKey(seq.incrementAndGet())] = Entry(epochMillis, slots)
    }

    @Reads
    override suspend fun layoutAt(holder: HolderId, asOfMillis: Long, limit: Int): List<ContainerSlotEntry>? {
        if (limit <= 0) return null
        val column = byHolder[holder] ?: return null
        var seen = 0
        for ((epochMillis, slots) in column.values) {
            if (epochMillis <= asOfMillis) return slots
            if (++seen >= limit) return null
        }
        return null
    }

    private companion object {
        /** Turns a sequence into a key that sorts newest first. */
        fun descKey(seq: Long): Long = Long.MAX_VALUE - seq
    }
}
