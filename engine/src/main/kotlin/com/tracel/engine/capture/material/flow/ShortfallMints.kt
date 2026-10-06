package com.tracel.engine.capture.material.flow

import com.tracel.engine.ledger.LotLedger
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity

/**
 * Prepends mint flows for every withdrawal in [flows] that the ledger cannot satisfy.
 *
 * The world is treated as the source of truth. If material left a holder, it must have been there;
 * the ledger simply never saw it arrive.
 *
 * That can happen with:
 * - Pre-plugin items
 * - Untracked transfers
 * - Snapshots that briefly lag reality
 * - Because of my fault
 *
 * Minting restores the missing balance (so the withdrawal can be recorded instead of disappearing).
 *
 * The caller decides which holders are safe to mint into via [mintable]. The important distinction
 * is between "not known yet" and "will never be known"-minting into the former duplicates material
 * once the delayed credit eventually arrives.
 *
 * Flows are processed in order, allowing later withdrawals to spend earlier deposits before minting
 * any remaining shortfall.
 */
public suspend fun LotLedger.shortfallMints(
    flows: List<Flow>,
    mintable: (HolderId) -> Boolean,
): List<Flow> = atomically {
    val balances = PendingBalances(this)
    val mints = mutableListOf<Flow>()

    flows.walk(
        debit = { holder, itemKey, amount ->
            if (holder !is HolderId.Source && holder !is HolderId.Sink && mintable(holder)) {
                val missing = amount - balances.balanceOf(holder, itemKey)
                if (missing > 0L) {
                    mints += Flow(
                        itemKey = itemKey,
                        quantity = Quantity(missing),
                        source = UNATTRIBUTED_SOURCE,
                        destination = holder,
                        kind = FlowKind.MINT
                    )
                    balances.add(holder, itemKey, missing)
                }
            }
            balances.add(holder, itemKey, -amount)
        },
        credit = balances::add,
    )
    mints
}
