package com.tracel.engine.capture.material

import com.tracel.model.transaction.CauseKind
import com.tracel.engine.capture.material.flow.apply
import com.tracel.engine.capture.material.flow.checkAllWithdrawalsSatisfiable
import com.tracel.engine.capture.material.flow.craftFlows
import com.tracel.engine.capture.material.flow.shortfallMints
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.log.TransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos

/** Balances inventory deltas, applies them to the ledger, and logs one [Transaction]. */
public class CaptureCoordinator(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextTxnId: suspend () -> TxnId,
    private val nextSeq: suspend () -> Seq,
) {
    /**
     * @return `null` when nothing moved — an empty diff is not a transaction.
     *
     * [mintShortfall] is for callers stating physical truth they watched happen. It names
     * the holders whose ignorance is permanent.
     *
     * @see shortfallMints
     */
    public suspend fun record(
        deltas: List<InventoryDelta>,
        epochMillis: Long,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos? = null,
        mintShortfall: ((HolderId) -> Boolean)? = null,
    ): Transaction? =
        recordDirect(TransactionBalancer().balance(deltas), epochMillis, cause, causedBy, at, mintShortfall)

    /**
     * Crafts through the ledger. Ingredients are already resolved, so
     * the balancer is skipped.
     */
    public suspend fun recordCraft(
        ingredients: List<Ingredient>,
        product: Product,
        epochMillis: Long,
        causedBy: HolderId?,
        at: BlockPos? = null,
    ): Transaction =
        ledger.atomically {
            val txn = nextTxnId()
            val crafted = ledger.craft(ingredients, product, txn)

            val flows = craftFlows(ingredients, product)

            val lots = crafted.consumed.flatMapIndexed { i, portions ->
                portions.map { FlowLot(i, it.lotId, it.quantity) }
            } + FlowLot(ingredients.size, crafted.output.id, product.quantity)

            val transaction = Transaction(txn, nextSeq(), epochMillis, CauseKind.CRAFT, causedBy, flows, lots, at)
            log.append(transaction)
            transaction
        }

    /**
     * Logs [flows] as-is. Use when the caller already knows the moves and balancing would
     * get them wrong.
     */
    public suspend fun recordDirect(
        flows: List<Flow>,
        epochMillis: Long,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos? = null,
        mintShortfall: ((HolderId) -> Boolean)? = null,
    ): Transaction? {
        if (flows.isEmpty()) return null
        return applyAndLog(flows, epochMillis, cause, causedBy, at, mintShortfall)
    }

    /** Rejects the whole transaction if any withdrawal cannot be satisfied. */
    private suspend fun applyAndLog(
        flows: List<Flow>,
        epochMillis: Long,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos?,
        mintShortfall: ((HolderId) -> Boolean)?,
    ): Transaction =
        ledger.atomically {
            // Prepended: the mint has to land before the withdrawal that needs it,
            // and it belongs in the logged flows so the row says where the material came from.
            val all = if (mintShortfall == null) flows else ledger.shortfallMints(flows, mintShortfall) + flows
            ledger.checkAllWithdrawalsSatisfiable(all)

            val txn = nextTxnId()
            val lots = all.flatMapIndexed { i, flow ->
                ledger.apply(flow, txn).map { FlowLot(i, it.lotId, it.quantity) }
            }

            val transaction = Transaction(txn, nextSeq(), epochMillis, cause, causedBy, all, lots, at)
            log.append(transaction)
            transaction
        }
}
