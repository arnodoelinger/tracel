package com.tracel.storage.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import java.util.UUID

/**
 * A canonical, parseable text encoding of [HolderId] for use as a `SQLite` column value.
 * `HolderId`'s own `toString()` is stable (see `HolderOrdering`) but was never meant to be
 * parsed back into a value — this is that other half.
 */
object HolderCodec {
    /** Encode a [HolderId] into a canonical, parseable text representation. */
    fun encode(holder: HolderId): String = when (holder) {
        is HolderId.Block -> "BLOCK:${holder.world.uuid}:${holder.x}:${holder.y}:${holder.z}"
        is HolderId.PlacedBlock -> "PLACED_BLOCK:${holder.world.uuid}:${holder.x}:${holder.y}:${holder.z}"
        is HolderId.Player -> "PLAYER:${holder.uuid}"
        is HolderId.Entity -> "ENTITY:${holder.uuid}"
        is HolderId.ItemEntity -> "ITEM_ENTITY:${holder.uuid}"
        is HolderId.Escrow -> "ESCROW:${holder.job.raw}"
        is HolderId.Source -> "SOURCE:${holder.kind.name}"
        is HolderId.Sink -> "SINK:${holder.kind.name}"
    }

    /** Decode a canonical, parseable text representation of a [HolderId] back into a value. */
    fun decode(text: String): HolderId {
        val parts = text.split(":")
        return when (parts[0]) {
            "BLOCK" -> HolderId.Block(WorldId(UUID.fromString(parts[1])), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
            "PLACED_BLOCK" -> HolderId.PlacedBlock(WorldId(UUID.fromString(parts[1])), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
            "PLAYER" -> HolderId.Player(UUID.fromString(parts[1]))
            "ENTITY" -> HolderId.Entity(UUID.fromString(parts[1]))
            "ITEM_ENTITY" -> HolderId.ItemEntity(UUID.fromString(parts[1]))
            "ESCROW" -> HolderId.Escrow(RollbackJobId(parts[1].toLong()))
            "SOURCE" -> HolderId.Source(SourceKind.valueOf(parts[1]))
            "SINK" -> HolderId.Sink(SinkKind.valueOf(parts[1]))
            else -> error("unrecognized holder encoding: $text")
        }
    }
}
