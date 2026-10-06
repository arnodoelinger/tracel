package com.tracel.engine.capture.material.flow

import com.tracel.engine.ledger.craft.Ingredient
import com.tracel.engine.ledger.craft.Product
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind

/** Flows a craft would log: ingredients consumed, product minted. */
public fun craftFlows(ingredients: List<Ingredient>, product: Product): List<Flow> =
    ingredients.map {
        Flow(
            itemKey = it.itemKey,
            quantity = it.quantity,
            source = it.holder,
            destination = HolderId.Sink(SinkKind.CRAFT_CONSUME),
            kind = FlowKind.TRANSFORM_IN)
    } + Flow(
        itemKey = product.itemKey,
        quantity = product.quantity,
        source = HolderId.Source(SourceKind.CRAFT),
        destination = product.holder,
        kind = FlowKind.TRANSFORM_OUT
    )
