package com.tracel.storage.ports.ledger

import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import java.util.*

/** One unit of material physical restorer owes (or owes back from) a player who was offline at the time. */
data class PendingDelivery(val id: Long, val itemKey: ItemKey, val delta: Long, val job: RollbackJobId)

/** A durable queue of [PendingDelivery]s, keyed by the player who is owed. */
class PendingDeliveryRepository(
    private val storage: TracelStorage,
    private val counters: Counters,
) {
    /**
     * Queues every entry in [deltas] for [player] as one durable batch.
     *
     * A crash partway through a multi-item-key restore must not leave some of it queued and the
     * rest silently owed to nobody, which is why this is one unit of work and not a loop of them.
     */
    suspend fun enqueueAll(player: UUID, deltas: Map<ItemKey, Long>, job: RollbackJobId, nowMillis: Long) {
        val ids = deltas.keys.map { counters.nextPendingDeliveryId() }
        storage.write {
            deltas.entries.forEachIndexed { index, (itemKey, delta) ->
                put(
                    Keys.pending(player, ids[index]),
                    Records.pending(storage.interning.internItemKey(this, itemKey), delta, job.raw, nowMillis),
                )
            }
        }
    }

    /** Atomically reads and deletes every entry owed to [player], in one unit of work. */
    suspend fun claimFor(player: UUID): List<PendingDelivery> = storage.write {
        val out = ArrayList<PendingDelivery>()
        val keys = ArrayList<ByteArray>()
        eachRow(Keys.pendingPrefix(player)) { cursor ->
            val value = cursor.value()
            out += PendingDelivery(
                KeyReader.u64(cursor.key(), 17),
                storage.interning.resolveItemKey(this, Records.pendingItemKeyId(value)),
                Records.pendingDelta(value),
                RollbackJobId(Records.pendingJobId(value)),
            )
            keys += cursor.key()
        }
        keys.forEach(::delete)
        out
    }
}
