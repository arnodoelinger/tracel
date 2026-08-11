package com.tracel.engine.provenance

import com.tracel.model.holder.HolderId
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

/**
 * One step in "what happened to this item" — a lot and where its material
 * went next.
 *
 * [currentHolder] is set only on a leaf (nothing further happened to this lot):
 * everywhere else, the material's story continues in [next].
 */
public data class FateNode(
    public val lotId: LotId,
    public val itemKey: ItemKey,
    public val currentHolder: HolderId?,
    public val next: List<FateNode>,
)
