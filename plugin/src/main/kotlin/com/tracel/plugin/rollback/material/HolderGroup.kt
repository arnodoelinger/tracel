package com.tracel.plugin.rollback.material

import com.tracel.model.holder.HolderId

/** How material restore buckets holders in the trace. */
internal enum class HolderGroup(val traceName: String) {
    PLAYERS("players"),
    CONTAINERS("containers"),
    OTHER("other");

    companion object {
        /** Separate holders. */
        fun of(holder: HolderId): HolderGroup = when (holder) {
            is HolderId.Player -> PLAYERS
            is HolderId.Block -> CONTAINERS
            else -> OTHER
        }
    }
}
