package com.tracel.engine.balance

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.TxnId

/**
 * Applies [flow] to this ledger — the mechanical half of what [TransactionBalancer] produces.
 */
public fun LotLedger.apply(flow: Flow, txn: TxnId) {
    when (flow.kind) {
        FlowKind.MOVE -> move(flow.source, flow.destination, flow.itemKey, flow.quantity, txn)
        FlowKind.MINT -> mint(flow.destination, flow.itemKey, flow.quantity, txn)
        FlowKind.BURN -> burn(flow.source, flow.itemKey, flow.quantity, (flow.destination as HolderId.Sink).kind, txn)
        FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT ->
            error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
    }
}
