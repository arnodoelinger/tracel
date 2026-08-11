package com.tracel.engine.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.engine.balance.apply
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.TransactionLog
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction

/**
 * The full capture pipeline in one call: raw deltas -> balanced [com.tracel.model.flow.Flow]s ->
 * applied to the ledger -> appended to the log as one [Transaction].
 *
 * [nextTxnId] and [nextSeq] are injected rather than read from a counter this class owns, for
 * the same reason [epochMillis] is a parameter rather than `System.currentTimeMillis()` read
 * internally: a capture pipeline that assigns its own ids can't be driven deterministically in
 * a test, and this is exactly the code [com.tracel.tests.property.DeterminismTest]-style
 * scenarios need to exercise without a live clock or a live counter getting in the way.
 */
public class CaptureCoordinator(
    private val ledger: LotLedger,
    private val log: TransactionLog,
    private val nextTxnId: () -> TxnId,
    private val nextSeq: () -> Seq,
) {
    /**
     * Balances [deltas] and, if anything actually changed, applies the result to the ledger and
     * appends it to the log. Returns `null` for an empty diff — a capture pass that saw nothing
     * move is not a transaction, and recording one anyway would just be log noise.
     */
    public fun record(deltas: List<InventoryDelta>, epochMillis: Long, cause: CauseKind, causedBy: HolderId?): Transaction? {
        val flows = TransactionBalancer().balance(deltas)
        if (flows.isEmpty()) return null

        val txn = nextTxnId()
        for (flow in flows) ledger.apply(flow, txn)

        val transaction = Transaction(txn, nextSeq(), epochMillis, cause, causedBy, flows)
        log.append(transaction)
        return transaction
    }
}
