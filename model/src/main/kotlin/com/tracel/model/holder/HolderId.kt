package com.tracel.model.holder

import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import java.util.*

/** Where a lot can sit. */
public sealed interface HolderId {
    /** A holder that sits at a block position. */
    public sealed interface AtBlock : HolderId {
        public val pos: BlockPos
        public val world: WorldId get() = pos.world
        public val x: Int get() = pos.x
        public val y: Int get() = pos.y
        public val z: Int get() = pos.z
    }

    /** A container block at these coordinates. */
    public data class Block(override val pos: BlockPos) : AtBlock {
        public constructor(world: WorldId, x: Int, y: Int, z: Int) : this(BlockPos(world, x, y, z))
    }

    /** The block itself, represented as the item you get when breaking it. */
    public data class PlacedBlock(override val pos: BlockPos) : AtBlock {
        public constructor(world: WorldId, x: Int, y: Int, z: Int) : this(BlockPos(world, x, y, z))
    }

    /** A player's inventory. */
    public data class Player(public val uuid: UUID) : HolderId

    /** A player's private stash, kept apart from their main inventory. */
    public data class PlayerStash(public val uuid: UUID) : HolderId

    /** An entity's inventory, such as a cart's cargo. */
    public data class Entity(public val uuid: UUID) : HolderId

    /** The entity itself, represented as the item it drops when broken. */
    public data class PlacedEntity(public val uuid: UUID) : HolderId

    /** A dropped item entity in the world. */
    public data class ItemEntity(public val uuid: UUID) : HolderId

    /** Temporary storage used while a rollback is running. */
    public data class Escrow(public val job: RollbackJobId) : HolderId

    /** Items entering the ledger from outside it. */
    public data class Source(public val kind: SourceKind) : HolderId

    /** Items leaving the ledger permanently. */
    public data class Sink(public val kind: SinkKind) : HolderId
}
