package com.tracel.engine.rollback

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/** Adds [amount] of [itemKey] to what [holder] is down for, a debit being negative. */
internal fun MutableMap<HolderId, MutableMap<ItemKey, Long>>.addDelta(holder: HolderId, itemKey: ItemKey, amount: Long) {
    getOrPut(holder) { mutableMapOf() }.merge(itemKey, amount, Long::plus)
}
