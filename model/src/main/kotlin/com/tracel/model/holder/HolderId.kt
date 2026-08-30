package com.tracel.model.holder

import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.WorldId
import java.util.UUID

/**
 * Where a lot can sit.
 *
 * [Source] and [Sink] are pseudo-holders, not real inventories — minting a unit
 * is modeled as a flow from a [Source], burning one as a flow to a [Sink].
 *
 * Treating them as ordinary holders (rather than a special case) is what lets
 * the same ledger code handle "picked up from the ground" and "burned in lava"
 * without an `if` for either.
 *
 * [Block] and [PlacedBlock] name the same coordinates and mean different things:
 * the first is what a container holds, the second is the block itself, as the
 * item it would be if you picked it up.
 *
 * [Entity] and [PlacedEntity] split the same way — a chest boat's cargo against
 * the boat. Without the second half a broken boat is an item appearing from
 * nowhere, which is exactly the kind of gap a rollback would have to mint its
 * way out of.
 */
public sealed interface HolderId {
    public data class Block(val world: WorldId, val x: Int, val y: Int, val z: Int) : HolderId
    public data class PlacedBlock(val world: WorldId, val x: Int, val y: Int, val z: Int) : HolderId
    public data class Player(val uuid: UUID) : HolderId
    public data class Entity(val uuid: UUID) : HolderId
    public data class PlacedEntity(val uuid: UUID) : HolderId
    public data class ItemEntity(val uuid: UUID) : HolderId
    public data class Escrow(val job: RollbackJobId) : HolderId
    public data class Source(val kind: SourceKind) : HolderId
    public data class Sink(val kind: SinkKind) : HolderId
}
