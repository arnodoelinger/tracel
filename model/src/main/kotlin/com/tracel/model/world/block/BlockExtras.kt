package com.tracel.model.world.block

import com.tracel.model.world.OpaqueBytes

/**
 * The structural detail a block entity carries beyond its block state (like sign text, spawner mob,
 * banner patterns, lectern page, command block command).
 *
 * [Opaque] is the whole stock surface and every modded one with it, because the platform will
 * not list this for us.
 */
public sealed interface BlockExtras {
    public class Opaque(bytes: ByteArray) : OpaqueBytes(bytes), BlockExtras
}
