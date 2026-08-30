package com.tracel.engine.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.Product
import com.tracel.engine.log.TransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
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
    /** Returns `null` when nothing moved — an empty diff is not a transaction. */
    public suspend fun record(
        deltas: List<InventoryDelta>,
        epochMillis: Long,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos? = null,
    ): Transaction? {
        val flows = TransactionBalancer().balance(deltas)
        if (flows.isEmpty()) return null
        return applyAndLog(flows, epochMillis, cause, causedBy, at)
    }

    /**
     * Crafts through the ledger. Ingredients are already resolved, so
     * the balancer is skipped.
     */
    public suspend fun recordCraft(ingredients: List<Ingredient>, product: Product, epochMillis: Long, causedBy: HolderId?): Transaction =
        ledger.atomically {
            val txn = nextTxnId()
            val crafted = ledger.craft(ingredients, product, txn)

            val flows = craftFlows(ingredients, product)

            val lots = crafted.consumed.flatMapIndexed { i, portions ->
                portions.map { FlowLot(i, it.lotId, it.quantity) }
            } + FlowLot(ingredients.size, crafted.output.id, product.quantity)

            val transaction = Transaction(txn, nextSeq(), epochMillis, CauseKind.CRAFT, causedBy, flows, lots)
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
    ): Transaction? {
        if (flows.isEmpty()) return null
        return applyAndLog(flows, epochMillis, cause, causedBy, at)
    }

    /** Rejects the whole transaction if any withdrawal cannot be satisfied. */
    private suspend fun applyAndLog(
        flows: List<Flow>,
        epochMillis: Long,
        cause: CauseKind,
        causedBy: HolderId?,
        at: BlockPos?,
    ): Transaction =
        ledger.atomically {
            ledger.checkAllWithdrawalsSatisfiable(flows)

            val txn = nextTxnId()
            val lots = flows.flatMapIndexed { i, flow ->
                ledger.apply(flow, txn).map { FlowLot(i, it.lotId, it.quantity) }
            }

            val transaction = Transaction(txn, nextSeq(), epochMillis, cause, causedBy, flows, lots, at)
            log.append(transaction)
            transaction
        }
}

/** Flows a craft would log: ingredients consumed, product minted. */
public fun craftFlows(ingredients: List<Ingredient>, product: Product): List<Flow> =
    ingredients.map {
        Flow(it.itemKey, it.quantity, it.holder, HolderId.Sink(SinkKind.CRAFT_CONSUME), FlowKind.TRANSFORM_IN)
    } + Flow(product.itemKey, product.quantity, HolderId.Source(SourceKind.CRAFT), product.holder, FlowKind.TRANSFORM_OUT)
