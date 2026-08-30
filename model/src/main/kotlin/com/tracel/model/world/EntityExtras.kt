package com.tracel.model.world

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
}
