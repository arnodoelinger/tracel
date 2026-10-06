package com.tracel.engine.provenance

import com.tracel.model.id.LotId
import com.tracel.model.id.TxnId
import com.tracel.model.item.ItemKey

/**
 * One step in "where did this item come from" — a lot and the transactions
 * that created it.
 *
 * [via] has more than one entry exactly when this lot was crafted from several
 * distinct ingredient lots: that is the only way a lot of ends up with more than
 * one parent.
 */
public data class ProvenanceNode(
    public val lotId: LotId,
    public val itemKey: ItemKey,
    public val createdBy: TxnId,
    public val via: List<ProvenanceNode>,
)
