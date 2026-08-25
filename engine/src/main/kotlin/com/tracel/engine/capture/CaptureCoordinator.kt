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
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/**
 * The full capture pipeline in one call: raw deltas -> balanced [com.tracel.model.flow.Flow]s ->
 * applied to the ledger -> appended to the log as one [Transaction].
 */
public class CaptureCoordinator(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextTxnId: suspend () -> TxnId,
    private val nextSeq: suspend () -> Seq,
) {
    /**
     * Balances [deltas] and, if anything actually changed, applies the result to the ledger and
     * appends it to the log. Returns `null` for an empty diff — a capture pass that saw nothing
     * move is not a transaction, and recording one anyway would just be log noise.
     */
    public suspend fun record(deltas: List<InventoryDelta>, epochMillis: Long, cause: CauseKind, causedBy: HolderId?): Transaction? {
        val flows = TransactionBalancer().balance(deltas)
        if (flows.isEmpty()) return null
        return applyAndLog(flows, epochMillis, cause, causedBy)
    }

    /**
     * Records a craft directly through [LotLedger.craft]. Ingredients and product are
     * already fully resolved by the caller, there is nothing left for [TransactionBalancer]
     * to balance.
     */
    public suspend fun recordCraft(ingredients: List<Ingredient>, product: Product, epochMillis: Long, causedBy: HolderId?): Transaction =
        ledger.atomically {
            val txn = nextTxnId()
            ledger.craft(ingredients, product, txn)

            val flows = craftFlows(ingredients, product)

            val transaction = Transaction(txn, nextSeq(), epochMillis, CauseKind.CRAFT, causedBy, flows)
            log.append(transaction)
            transaction
        }

    /**
     * Applies a caller-resolved [flows] directly, skipping [TransactionBalancer] entirely.
     *
     * For cases where the caller already knows exactly what happened and [TransactionBalancer]'s
     * defaults would be wrong.
     */
    public suspend fun recordDirect(flows: List<Flow>, epochMillis: Long, cause: CauseKind, causedBy: HolderId?): Transaction? {
        if (flows.isEmpty()) return null
        return applyAndLog(flows, epochMillis, cause, causedBy)
    }

    /**
     * All or nothing: if any withdrawal is impossible, the whole transaction is invalid and must be
     * rejected.
     *
     * We're not schizophrenic enough to try to apply a partial transaction and then roll it
     * back if one flow fails, right?
     */
    private suspend fun applyAndLog(flows: List<Flow>, epochMillis: Long, cause: CauseKind, causedBy: HolderId?): Transaction =
        ledger.atomically {
            ledger.checkAllWithdrawalsSatisfiable(flows)

            val txn = nextTxnId()
            for (flow in flows) ledger.apply(flow, txn)

            val transaction = Transaction(txn, nextSeq(), epochMillis, cause, causedBy, flows)
            log.append(transaction)
            transaction
        }
}

/**
 * Converts a craft's ingredients and product into the [Flow]s that would be recorded for it.
 *
 * This is the same as what [TransactionBalancer] would produce, but the caller already knows
 * exactly what happened and [TransactionBalancer]'s defaults would be wrong, so it can skip
 * the balancing step and just call this to get the right flows.
 */
public fun craftFlows(ingredients: List<Ingredient>, product: Product): List<Flow> =
    ingredients.map {
        Flow(it.itemKey, it.quantity, it.holder, HolderId.Sink(SinkKind.CRAFT_CONSUME), FlowKind.TRANSFORM_IN)
    } + Flow(product.itemKey, product.quantity, HolderId.Source(SourceKind.CRAFT), product.holder, FlowKind.TRANSFORM_OUT)
