package com.tracel.engine.ledger

import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.item.ItemKey

/** One ingredient a craft consumes. */
public data class Ingredient(public val holder: HolderId, public val itemKey: ItemKey, public val quantity: Quantity)

/**
 * What a craft produces. Exactly one product per craft, matching vanilla
 * Minecraft recipes — a shared recipe with several genuinely distinct output
 * stacks does not exist there, so [LotLedger.craft] does not need to support
 * one.
 */
public data class Product(public val holder: HolderId, public val itemKey: ItemKey, public val quantity: Quantity)
