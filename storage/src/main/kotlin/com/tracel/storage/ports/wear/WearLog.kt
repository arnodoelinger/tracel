package com.tracel.storage.ports.wear

import com.tracel.engine.wear.WearMark
import com.tracel.model.id.LotId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.records.Wear
import com.tracel.storage.ports.log.walkWanted
import com.tracel.storage.ports.ops.Counters
import com.tracel.engine.wear.WearLog as WearLogPort

/**
 * How worn each tool was over time, one row per change, keyed by its lot.
 *
 * @see [WearLogPort].
 */
class WearLog(
    private val storage: TracelStorage,
    private val counters: Counters,
) : WearLogPort {
    override suspend fun record(mark: WearMark) {
        val seq = counters.nextSeq().raw
        storage.write { put(Keys.wear(mark.lotId.raw, seq), Wear.mark(mark.epochMillis, mark.before, mark.after)) }
    }

    override suspend fun marksOf(lots: Collection<LotId>): Map<LotId, List<WearMark>> {
        if (lots.isEmpty()) return emptyMap()
        val sorted =
            LongArray(lots.size).also { raws -> lots.forEachIndexed { i, lot -> raws[i] = lot.raw } }.distinct()
                .toLongArray()
        sorted.sort()
        return storage.read {
            val out = HashMap<LotId, MutableList<WearMark>>()
            walkWanted(Keys.WEAR, sorted, 0, sorted.size, Keys::wearPrefix) { at, cursor ->
                val value = cursor.value()
                val lot = LotId(at)
                out.getOrPut(lot) { ArrayList() } += WearMark(
                    lot,
                    Wear.epochMillis(value),
                    Wear.before(value),
                    Wear.after(value)
                )
            }
            out
        }
    }
}
