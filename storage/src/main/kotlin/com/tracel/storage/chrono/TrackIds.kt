package com.tracel.storage.chrono

import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.capsule.CapsuleView
import com.tracel.storage.Interning

/**
 * The IDs a capsule is filed under in the track index: the players in it,
 * and on the items lane the items, negated.
 */
internal object TrackIds {
    fun of(interning: Interning, unit: StorageUnit, lane: Lane, view: CapsuleView): IntArray {
        if (lane == Lane.CONTAINERS) return IntArray(0)
        return view.trackIds.filter { id -> id < 0 || isPlayer(interning, unit, id) }.toIntArray()
    }

    fun isPlayer(interning: Interning, unit: StorageUnit, id: Int): Boolean =
        id != 0 && runCatching { interning.resolveHolder(unit, id) }.getOrNull() is HolderId.Player
}
