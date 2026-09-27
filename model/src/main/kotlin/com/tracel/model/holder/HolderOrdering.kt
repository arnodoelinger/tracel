package com.tracel.model.holder

/**
 * A stable sort key for a holder.
 *
 * Balancer pairs gains against losses in a deterministic order.
 */
public fun HolderId.stableSortKey(): String = toString()
