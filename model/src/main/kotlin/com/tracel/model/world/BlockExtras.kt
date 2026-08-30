package com.tracel.model.world

/**
 * The structural detail a block entity carries beyond its block state (like sign text, spawner mob,
 * banner patterns, lectern page, command block command).
 *
 * [Opaque] is the whole vanilla surface and every modded one with it, because the platform will
 * not enumerate this for us.
 */
public sealed interface BlockExtras {
    public class Opaque(public val nbt: ByteArray) : BlockExtras {
        override fun equals(other: Any?): Boolean = other is Opaque && nbt.contentEquals(other.nbt)

        override fun hashCode(): Int = nbt.contentHashCode()

        override fun toString(): String = "Opaque(${nbt.size} bytes)"
    }
}
