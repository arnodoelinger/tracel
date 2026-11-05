package com.tracel.storage.ports.ledger

import com.tracel.engine.ledger.PendingDelivery
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.Pending
import com.tracel.storage.ports.ops.Counters
import com.tracel.storage.util.eachRow
import java.util.*
import com.tracel.engine.ledger.PendingDeliveryRepository as PendingDeliveryRepositoryPort

class PendingDeliveryRepository(
    private val storage: TracelStorage,
    private val counters: Counters,
) : PendingDeliveryRepositoryPort {
    override suspend fun enqueueAll(
        player: UUID,
        deltas: Map<ItemKey, Long>,
        job: RollbackJobId,
        nowMillis: Long,
        stash: Boolean,
    ) {
        val ids = deltas.keys.map { counters.nextPendingDeliveryId() }
        storage.write {
            deltas.entries.forEachIndexed { index, (itemKey, delta) ->
                put(
                    Tag.PENDING[player, ids[index]],
                    Pending.of(
                        storage.interning.internItemKey(this, itemKey),
                        delta,
                        job.raw,
                        nowMillis,
                        stash
                    ),
                )
            }
        }
    }

    override suspend fun claimFor(player: UUID): List<PendingDelivery> = storage.write {
        val out = ArrayList<PendingDelivery>()
        val keys = ArrayList<ByteArray>()
        eachRow(Tag.PENDING[player]) { cursor ->
            val value = cursor.value()
            out += PendingDelivery(
                KeyReader.u64(cursor.key(), 17),
                storage.interning.resolveItemKey(this, Pending.itemKeyId[value]),
                Pending.delta[value],
                RollbackJobId(Pending.jobId[value]),
                Pending.enderChest(value),
            )
            keys += cursor.key()
        }
        keys.forEach(::delete)
        out
    }
}
