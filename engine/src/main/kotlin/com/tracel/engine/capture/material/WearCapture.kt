package com.tracel.engine.capture.material

import com.tracel.model.transaction.CauseKind
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.wear.WearLog
import com.tracel.engine.wear.WearMark
import com.tracel.engine.wear.damageNow
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos

/**
 * A tool wearing down or being repaired, pinned to the lot the ledger holds it as.
 *
 * Nothing moves. The row exists so a rollback reaches the lot the way it reaches any other: by who, when and where.
 */
public class WearCapture(
    private val repo: LotRepository,
    private val log: TransactionLog,
    private val wear: WearLog,
    private val nextTxnId: suspend () -> TxnId,
    private val nextSeq: suspend () -> Seq,
) {
    /** @return `null` when [holder] has no lot of [itemKey] to pin the change on. */
    public suspend fun record(
        holder: HolderId,
        itemKey: ItemKey,
        before: Int,
        after: Int,
        epochMillis: Long,
        at: BlockPos?,
    ): Transaction? = repo.atomically {
        if (before == after) return@atomically null
        val lot = wornLot(holder, itemKey, before) ?: return@atomically null
        wear.record(WearMark(lot, epochMillis, before, after))
        val transaction = Transaction(
            id = nextTxnId(),
            seq = nextSeq(),
            epochMillis = epochMillis,
            cause = CauseKind.WEAR,
            causedBy = holder,
            flows = listOf(Flow(itemKey, Quantity(1), holder, holder, FlowKind.MOVE)),
            lots = listOf(FlowLot(0, lot, Quantity(1))),
            at = at,
        )
        log.append(transaction)
        transaction
    }

    private suspend fun wornLot(holder: HolderId, itemKey: ItemKey, before: Int): LotId? {
        val queue = repo.accountQueue(holder, itemKey)
        if (queue.size <= 1) return queue.firstOrNull()?.lot?.id
        val marks = wear.marksOf(queue.map { it.lot.id })
        return queue.firstOrNull { marks[it.lot.id]?.damageNow == before }?.lot?.id
            ?: queue.firstOrNull { it.lot.id !in marks }?.lot?.id
            ?: queue.first().lot.id
    }
}
