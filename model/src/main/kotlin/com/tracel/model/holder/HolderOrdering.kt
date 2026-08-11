package com.tracel.model.holder

/**
 * A stable sort key for a holder.
 *
 * [TransactionBalancer][com.tracel.engine.balance.TransactionBalancer] pairs
 * gains against losses in a deterministic order.
 */
public fun HolderId.stableSortKey(): String = toString()
