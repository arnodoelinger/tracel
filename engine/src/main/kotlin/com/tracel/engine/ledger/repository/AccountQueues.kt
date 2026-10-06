package com.tracel.engine.ledger.repository

import com.tracel.annotations.Consume
import com.tracel.engine.ledger.LotPortion
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.AccountLot
import com.tracel.model.transaction.TxnId

/** What each holder holds of each item, oldest lot first, and the consuming of it. */
public interface AccountQueues {
    /**
     * [holder]'s queue for [itemKey], oldest first, at most [limit] entries.
     *
     * [limit] exists because a withdrawal almost never needs the whole queue: it takes from the
     * oldest lots until it has enough, and an account that has accumulated a few thousand
     * placements should not cost a few thousand rows to take one coin out of.
     *
     * Callers that genuinely want the whole queue — a provenance walk. Say, simply leave it alone.
     */
    public suspend fun accountQueue(holder: HolderId, itemKey: ItemKey, limit: Int = Int.MAX_VALUE): List<AccountLot>

    /** FIFO-consume [quantity] from the live queue, oldest first, splitting the last lot. */
    @Consume
    public suspend fun takeFifo(
        holder: HolderId,
        itemKey: ItemKey,
        quantity: Quantity,
        txn: TxnId,
    ): List<LotPortion>

    /**
     * Same FIFO consume as [takeFifo], handed to several destinations in [owed] order, one pass.
     */
    @Consume
    public suspend fun drainFifo(
        holder: HolderId,
        itemKey: ItemKey,
        owed: List<Pair<HolderId, Long>>,
        txn: TxnId,
    ): List<Pair<HolderId, List<LotPortion>>>

    /** What [holder] holds of [itemKey] in total, without reading the queue that says so. */
    public suspend fun totalOf(holder: HolderId, itemKey: ItemKey): Long

    /** Everything [holder] holds, summed per item key. The whole-account form of [totalOf]. */
    public suspend fun totalsAt(holder: HolderId): Map<ItemKey, Long>
}
