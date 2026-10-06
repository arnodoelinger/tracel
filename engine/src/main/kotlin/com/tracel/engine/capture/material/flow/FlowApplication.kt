package com.tracel.engine.capture.material.flow

import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.LotPortion
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.transaction.TxnId

/** The ledger never saw this arrive, and nobody can say where it came from. */
public val UNATTRIBUTED_SOURCE: HolderId.Source = HolderId.Source(SourceKind.UNATTRIBUTED)

/**
 * Applies one [flow] to the ledger.
 *
 * Returns the portions that actually moved. The flow says how many, the lots say which ones.
 *
 * Crafts are in [LotLedger.craft].
 *
 * @see [Flow]
 * @see [LotLedger.craft]
 */
public suspend fun LotLedger.apply(flow: Flow, txn: TxnId): List<LotPortion> =
    when (flow.kind) {
        FlowKind.MOVE -> {
            move(flow.source, flow.destination, flow.itemKey, flow.quantity, txn)
        }

        FlowKind.MINT -> {
            listOf(LotPortion(mint(flow.destination, flow.itemKey, flow.quantity, txn).id, flow.quantity))
        }

        FlowKind.BURN -> {
            burn(flow.source, flow.itemKey, flow.quantity, (flow.destination as HolderId.Sink).kind, txn)
        }

        FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT -> {
            notAFlowToApply()
        }
    }
