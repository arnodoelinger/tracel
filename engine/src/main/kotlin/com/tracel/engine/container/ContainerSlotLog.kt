package com.tracel.engine.container

import com.tracel.model.holder.HolderId

/**
 * What sat in which slot of a container, as it was every time somebody looked.
 *
 * Quantities live in the ledger and are blind to slots, so this is what a restore reads to put things back where they
 * were.
 */
public interface ContainerSlotLog {
    /** Records the whole slot layout of [holder] as it was at [epochMillis]. */
    public suspend fun record(holder: HolderId, epochMillis: Long, slots: List<ContainerSlotEntry>)

    /**
     * The newest layout [holder] had at or before [asOfMillis], or `null` if none qualifies. Looks through at most
     * [limit] recorded layouts, newest first, before giving up.
     */
    public suspend fun layoutAt(holder: HolderId, asOfMillis: Long, limit: Int = 64): List<ContainerSlotEntry>?
}
