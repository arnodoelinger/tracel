package com.tracel.engine.ledger

import com.tracel.model.item.ItemKey
import java.util.*

/** The real stacks behind a [PendingDelivery], kept for the player who is owed them. */
public interface PendingWear {
    /** Keeps every stack in [stacks] for [player], as one durable batch. */
    public suspend fun keepAll(player: UUID, stacks: Map<ItemKey, List<ByteArray>>)

    /**
     * Atomically reads and deletes every stack kept for [player],
     * in the order they were kept.
     */
    public suspend fun claimFor(player: UUID): Map<ItemKey, List<ByteArray>>
}
