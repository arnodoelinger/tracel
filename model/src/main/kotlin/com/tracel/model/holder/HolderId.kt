package com.tracel.model.holder

import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import java.util.*

/** Where a lot can sit. */
public sealed interface HolderId {
    /** A container block at these coordinates. */
    public data class Block(
        val world: WorldId,
        val x: Int,
        val y: Int,
        val z: Int
    ) : HolderId

    /** The block itself, represented as the item you get when breaking it. */
    public data class PlacedBlock(
        val world: WorldId,
        val x: Int,
        val y: Int,
        val z: Int
    ) : HolderId

    /** A player's inventory. */
    public data class Player(val uuid: UUID) : HolderId

    /** A player's private stash, kept apart from their main inventory. */
    public data class PlayerStash(val uuid: UUID) : HolderId

    /** An entity's inventory, such as a cart's cargo. */
    public data class Entity(val uuid: UUID) : HolderId

    /** The entity itself, represented as the item it drops when broken. */
    public data class PlacedEntity(val uuid: UUID) : HolderId

    /** A dropped item entity in the world. */
    public data class ItemEntity(val uuid: UUID) : HolderId

    /** Temporary storage used while a rollback is running. */
    public data class Escrow(val job: RollbackJobId) : HolderId

    /** Items entering the ledger from outside it. */
    public data class Source(val kind: SourceKind) : HolderId

    /** Items leaving the ledger permanently. */
    public data class Sink(val kind: SinkKind) : HolderId
}
