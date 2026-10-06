package com.tracel.engine.capture.material.flow

import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/**
 * Walks [this] in order and says, for each flow, which account loses and which gains.
 *
 * A move debits its source and credits its destination, a burn only debits, a mint only credits. Crafts are not
 * flows to apply generically: they come from the ledger's own craft.
 */
internal suspend fun List<Flow>.walk(
    debit: suspend (HolderId, ItemKey, Long) -> Unit,
    credit: (HolderId, ItemKey, Long) -> Unit,
) {
    for ((itemKey, quantity, source, destination, kind) in this) {
        when (kind) {
            FlowKind.MOVE -> {
                debit(source, itemKey, quantity.raw)
                credit(destination, itemKey, quantity.raw)
            }

            FlowKind.BURN -> debit(source, itemKey, quantity.raw)
            FlowKind.MINT -> credit(destination, itemKey, quantity.raw)
            FlowKind.TRANSFORM_IN, FlowKind.TRANSFORM_OUT -> notAFlowToApply()
        }
    }
}

internal fun notAFlowToApply(): Nothing =
    error("TRANSFORM flows come from LotLedger.craft() directly, not from applying a Flow generically")
