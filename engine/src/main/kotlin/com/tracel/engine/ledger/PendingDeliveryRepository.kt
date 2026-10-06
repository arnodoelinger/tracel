package com.tracel.engine.ledger

import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import java.util.UUID

/** One unit of material a restore owes (or owes back from) a player who was offline at the time. */
public data class PendingDelivery(
    public val id: Long,
    public val itemKey: ItemKey,
    public val delta: Long,
    public val job: RollbackJobId,
    public val stash: Boolean = false,
)

/** A durable queue of [PendingDelivery]s, keyed by the player who is owed. */
public interface PendingDeliveryRepository {
    /** Queues every entry in [deltas] for [player] as one durable batch. */
    public suspend fun enqueueAll(
        player: UUID,
        deltas: Map<ItemKey, Long>,
        job: RollbackJobId,
        nowMillis: Long,
        stash: Boolean = false,
    )

    /** Atomically reads and deletes every entry owed to [player]. */
    public suspend fun claimFor(player: UUID): List<PendingDelivery>
}
