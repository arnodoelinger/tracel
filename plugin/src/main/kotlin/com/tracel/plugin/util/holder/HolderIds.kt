package com.tracel.plugin.util.holder

import com.tracel.engine.log.TransactionLog
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import java.util.*

/** Where this holder sits, when it sits anywhere a block does. */
fun HolderId.blockPos(): BlockPos? = when (this) {
    is HolderId.AtBlock -> pos
    is HolderId.Entity,
    is HolderId.PlacedEntity,
    is HolderId.ItemEntity,
    is HolderId.Player,
    is HolderId.PlayerStash,
    is HolderId.Source,
    is HolderId.Sink,
    is HolderId.Escrow,
        -> null
}

/** Which world this holder is in, when it names one. */
fun HolderId.worldId(): WorldId? = when (this) {
    is HolderId.AtBlock -> world
    is HolderId.Entity,
    is HolderId.PlacedEntity,
    is HolderId.ItemEntity,
    is HolderId.Player,
    is HolderId.PlayerStash,
    is HolderId.Source,
    is HolderId.Sink,
    is HolderId.Escrow,
        -> null
}

/** The entity this holder is about, when it is one that can simply stop existing. */
fun HolderId.entityUuid(): UUID? = when (this) {
    is HolderId.Entity -> uuid
    is HolderId.PlacedEntity -> uuid
    is HolderId.ItemEntity -> uuid
    is HolderId.AtBlock,
    is HolderId.Player,
    is HolderId.PlayerStash,
    is HolderId.Source,
    is HolderId.Sink,
    is HolderId.Escrow,
        -> null
}

/**
 * Whether this holder is an entity a rollback may find gone.
 *
 * @see entityUuid
 */
fun HolderId.namedByEntity(): Boolean = entityUuid() != null

/**
 * Whether this holder puts the transaction it appears in on the spatial index without help.
 *
 * @see TransactionLog
 */
fun HolderId.carriesCoordinates(): Boolean = this is HolderId.AtBlock
