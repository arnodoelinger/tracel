package com.tracel.storage.ledger

import com.tracel.engine.ledger.PendingDelivery
import com.tracel.engine.ledger.PendingDeliveryRepository
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.admin.StoredCounters
import com.tracel.storage.codec.Pending
import com.tracel.storage.codec.Tag
import com.tracel.storage.table.ClaimQueue
import com.tracel.storage.table.KeyCodec
import com.tracel.storage.table.ValueCodec
import java.lang.foreign.MemorySegment
import java.util.*

/** A durable queue of [PendingDelivery]s, keyed by the player who is owed. */
class StoredPendingDeliveryRepository(storage: TracelStorage, counters: StoredCounters) : PendingDeliveryRepository {
    private val queue = ClaimQueue(storage, Tag.PENDING, KeyCodec.uuid, Owed, counters::nextPendingDeliveryId)

    override suspend fun enqueueAll(
        player: UUID,
        deltas: Map<ItemKey, Long>,
        job: RollbackJobId,
        nowMillis: Long,
        stash: Boolean,
    ) = queue.addAll(player, deltas.map { (itemKey, delta) -> Entry(itemKey, delta, job, nowMillis, stash) })

    override suspend fun claimFor(player: UUID): List<PendingDelivery> =
        queue.claim(player).map { (id, e) -> PendingDelivery(id, e.itemKey, e.delta, e.job, e.stash) }

    private class Entry(
        val itemKey: ItemKey,
        val delta: Long,
        val job: RollbackJobId,
        val millis: Long,
        val stash: Boolean
    )

    private object Owed : ValueCodec<Entry> {
        override fun write(storage: TracelStorage, unit: StorageUnit, value: Entry): ByteArray = Pending.of(
            storage.interning.internItemKey(unit, value.itemKey), value.delta, value.job.raw, value.millis, value.stash,
        )

        override fun read(storage: TracelStorage, unit: StorageUnit, value: MemorySegment) = Entry(
            storage.interning.resolveItemKey(unit, Pending.itemKeyId[value]),
            Pending.delta[value],
            RollbackJobId(Pending.jobId[value]),
            Pending.createdMillis[value],
            Pending.enderChest(value),
        )
    }
}
