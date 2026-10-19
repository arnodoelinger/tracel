package com.tracel.api.item

import com.tracel.api.Unstable
import com.tracel.api.world.BlockPlace
import java.util.UUID

/** Where items sat before or after an item flow. */
@Unstable
public sealed interface ItemHolder {
    /** A container block: a chest, a furnace, a hopper. */
    public data class Container(public val place: BlockPlace) : ItemHolder

    /** The block itself, as the item it breaks into. */
    public data class PlacedBlock(public val place: BlockPlace) : ItemHolder

    /** A player's inventory; [stash] for the private one kept apart from it. */
    public data class Player(public val uuid: UUID, public val stash: Boolean = false) : ItemHolder

    /** An entity's inventory: a cart's cargo, a llama's chest. */
    public data class Entity(public val uuid: UUID) : ItemHolder

    /** The entity itself, as the item it drops: an item frame, an armor stand. */
    public data class PlacedEntity(public val uuid: UUID) : ItemHolder

    /** An item lying on the ground. */
    public data class Dropped(public val uuid: UUID) : ItemHolder

    /** A rollback holding items on their way back. */
    public data object Rollback : ItemHolder

    /** Nowhere: the items came into being here, for [kind]. */
    public data class Appeared(public val kind: SourceKind) : ItemHolder

    /** Nowhere: the items ended here, for [kind]. */
    public data class Gone(public val kind: SinkKind) : ItemHolder
}
