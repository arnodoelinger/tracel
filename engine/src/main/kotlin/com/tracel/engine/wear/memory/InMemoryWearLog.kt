package com.tracel.engine.wear.memory

import com.tracel.annotations.Reads
import com.tracel.annotations.RunsOn
import com.tracel.annotations.SingleWriter
import com.tracel.annotations.ThreadContext
import com.tracel.platform.concurrency.SingleWriterGuard
import com.tracel.model.id.LotId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import com.tracel.engine.wear.WearLog
import com.tracel.engine.wear.WearMark

/** In-memory [WearLog], the reference the stored one is checked against. */
@SingleWriter
@RunsOn(ThreadContext.STORAGE)
public class InMemoryWearLog : WearLog {
    private val writer = SingleWriterGuard()
    private val byLot = ConcurrentHashMap<LotId, CopyOnWriteArrayList<WearMark>>()

    override suspend fun record(mark: WearMark) {
        writer.checkIn()
        byLot.getOrPut(mark.lotId) { CopyOnWriteArrayList() } += mark
    }

    @Reads
    override suspend fun marksOf(lots: Collection<LotId>): Map<LotId, List<WearMark>> {
        val out = HashMap<LotId, List<WearMark>>()
        for (lot in lots) byLot[lot]?.takeIf { it.isNotEmpty() }?.let { out[lot] = it.toList() }
        return out
    }
}
