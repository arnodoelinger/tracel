package com.tracel.engine.container

import com.tracel.model.item.ItemKey

/** What one occupied slot of a container held: [quantity] of [itemKey] in slot number [slot]. */
public data class ContainerSlotEntry(public val slot: Int, public val itemKey: ItemKey, public val quantity: Long)
