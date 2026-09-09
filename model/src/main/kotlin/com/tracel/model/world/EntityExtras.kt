package com.tracel.model.world

import java.util.UUID

/**
 * An entity's structural detail like frame rotation, armor stand pose, painting art, for example.
 *
 * @see [BlockExtras].
 */
public sealed interface EntityExtras {
    public class Opaque(public val nbt: ByteArray) : EntityExtras {
        override fun equals(other: Any?): Boolean = other is Opaque && nbt.contentEquals(other.nbt)
        override fun hashCode(): Int = nbt.contentHashCode()
        override fun toString(): String = "Opaque(${nbt.size} bytes)"
    }

    /**
     * What this entity is falling through, wrapped around whatever else its snapshot carries.
     *
     * [data] is the block type and data value, as if the entity were a block.
     */
    public data class Falling(public val data: BlockDataKey) : EntityExtras

    /**
     * What this entity is tied to, wrapped around whatever else its snapshot carries.
     *
     * [holder] is the leash knot on the fence, or in general whatever else is holding
     * the other end.
     */
    public data class Leashed(public val holder: UUID, public val rest: EntityExtras?) : EntityExtras

    /**
     * What this entity is sitting in, wrapped around whatever else its snapshot carries.
     *
     * The same bargain as [Leashed], for the same reason, and one more of its own: a vehicle's
     * passengers are not in its snapshot at all — serializing them is a flag this plugin does
     * not pass, because a boat that carried its riders in its own blob would spawn copies of
     * them on every restore. Recorded on the passenger, so each entity's own row says where it
     * sat and no record has to hold a list.
     */
    public data class Riding(public val vehicle: UUID, public val rest: EntityExtras?) : EntityExtras
}

/** @return the opaque snapshot in here, however, many links are wrapped around it. */
public val EntityExtras?.opaque: EntityExtras.Opaque?
    get() = when (this) {
        is EntityExtras.Opaque -> this
        is EntityExtras.Leashed -> rest.opaque
        is EntityExtras.Riding -> rest.opaque
        else -> null
    }

/** @return what this entity is tied to, or `null` for nothing. */
public val EntityExtras?.leashHolder: UUID?
    get() = when (this) {
        is EntityExtras.Leashed -> holder
        is EntityExtras.Riding -> rest.leashHolder
        else -> null
    }

/** @return what this entity is riding, or `null` for nothing. */
public val EntityExtras?.vehicle: UUID?
    get() = when (this) {
        is EntityExtras.Riding -> vehicle
        is EntityExtras.Leashed -> rest.vehicle
        else -> null
    }

/** [extras] with [holder] on it, or [extras] unchanged when there is no leash to record. */
public fun leashed(extras: EntityExtras?, holder: UUID?): EntityExtras? =
    if (holder == null) extras else EntityExtras.Leashed(holder, extras)

/** [extras] with [vehicle] on it, or [extras] unchanged when this entity is riding nothing. */
public fun riding(extras: EntityExtras?, vehicle: UUID?): EntityExtras? =
    if (vehicle == null) extras else EntityExtras.Riding(vehicle, extras)
