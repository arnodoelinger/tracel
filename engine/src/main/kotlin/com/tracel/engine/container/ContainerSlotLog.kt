package com.tracel.engine.container

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey

/** One occupied slot inside a container. */
public data class ContainerSlotEntry(public val slot: Int, public val itemKey: ItemKey, public val quantity: Long)

/** What sat in which slot of a container. */
public interface ContainerSlotLog {
    /** Records [holder]'s full slot layout at [epochMillis]. */
    public suspend fun record(holder: HolderId.Block, epochMillis: Long, slots: List<ContainerSlotEntry>)

    /** The newest layout [holder] had at or before [asOfMillis], or `null` if none qualifies. */
    public suspend fun layoutAt(holder: HolderId.Block, asOfMillis: Long, limit: Int = 64): List<ContainerSlotEntry>?
}
