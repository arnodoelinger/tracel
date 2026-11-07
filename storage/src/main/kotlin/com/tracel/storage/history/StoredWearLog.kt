package com.tracel.storage.history

import com.tracel.engine.wear.WearLog
import com.tracel.engine.wear.WearMark
import com.tracel.model.lot.LotId
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Wear

/**
 * How worn each tool was over time, one row per change, keyed by its lot.
 *
 * @see [WearLog].
 */
class StoredWearLog(
    private val storage: TracelStorage,
    private val counters: StoredCounters,
) : WearLog {
    override suspend fun record(mark: WearMark) {
        val seq = counters.nextSeq().raw
        storage.write { put(Tag.WEAR[mark.lotId.raw, seq], Wear.mark(mark.epochMillis, mark.before, mark.after)) }
    }

    override suspend fun marksOf(lots: Collection<LotId>): Map<LotId, List<WearMark>> {
        if (lots.isEmpty()) return emptyMap()
        val sorted =
            LongArray(lots.size).also { raws -> lots.forEachIndexed { i, lot -> raws[i] = lot.raw } }.distinct()
                .toLongArray()
        sorted.sort()
        return storage.read {
            val out = HashMap<LotId, MutableList<WearMark>>()
            walkWanted(Tag.WEAR, sorted, 0, sorted.size) { at, cursor ->
                val value = cursor.value()
                val lot = LotId(at)
                out.getOrPut(lot) { ArrayList() } += WearMark(
                    lot,
                    Wear.epochMillis[value],
                    Wear.before[value],
                    Wear.after[value]
                )
            }
            out
        }
    }
}
