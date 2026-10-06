package com.tracel.model.world.block

/**
 * The structural detail a block entity carries beyond its block state (like sign text, spawner mob,
 * banner patterns, lectern page, command block command).
 *
 * [Opaque] is the whole vanilla surface and every modded one with it, because the platform will
 * not list this for us.
 */
public sealed interface BlockExtras {
    public class Opaque(public val bytes: ByteArray) : BlockExtras {
        override fun equals(other: Any?): Boolean = other is Opaque && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()

        override fun toString(): String = "Opaque(${bytes.size} bytes)"
    }
}
