package com.tracel.storage.chrono

import com.tracel.model.cause.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.storage.codec.Tag
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where chrono keeps things, key by key.
 *
 * Fragments carry no track or time rows: they are few and short-lived,
 * and a walk reads them all.
 */
internal object ChronoKeys {
    val NONE = ByteArray(0)

    fun capsule(tile: Tile, lane: Lane, last: Long): ByteArray =
        Tag.CHRONO_CAPSULE[tile.world, tile.x, tile.z, lane.id, last]

    fun capsules(tile: Tile, lane: Lane): ByteArray = Tag.CHRONO_CAPSULE[tile.world, tile.x, tile.z, lane.id]

    fun fragment(tile: Tile, lane: Lane, last: Long): ByteArray =
        Tag.CHRONO_FRAGMENT[tile.world, tile.x, tile.z, lane.id, last]

    fun fragments(tile: Tile, lane: Lane): ByteArray = Tag.CHRONO_FRAGMENT[tile.world, tile.x, tile.z, lane.id]

    fun track(actor: Int, last: Long, tile: Tile, lane: Lane): ByteArray =
        Tag.CHRONO_TRACK[actor, last, tile.world, tile.x, tile.z, lane.id]

    fun time(last: Long, tile: Tile, lane: Lane): ByteArray = Tag.CHRONO_TIME[last, tile.world, tile.x, tile.z, lane.id]

    fun timeValue(lane: Lane, firstSeq: Long, firstMillis: Long): ByteArray =
        ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN).put(lane.id.toByte()).putLong(firstSeq)
            .putLong(firstMillis).array()

    fun trackValue(lane: Lane): ByteArray = byteArrayOf(lane.id.toByte())

    inline fun filing(
        tile: Tile,
        lane: Lane,
        firstSeq: Long,
        lastSeq: Long,
        firstMillis: Long,
        actors: IntArray,
        bytes: ByteArray,
        put: (ByteArray, ByteArray) -> Unit
    ) {
        put(capsule(tile, lane, lastSeq), bytes)
        put(time(lastSeq, tile, lane), timeValue(lane, firstSeq, firstMillis))
        val tracked = trackValue(lane)
        for (id in actors) if (id != 0) put(track(id, lastSeq, tile, lane), tracked)
    }

    fun tileOf(key: ByteArray, tag: Tag): Tile = Tile.of(tag.int(key, 0), tag.int(key, 1), tag.int(key, 2))
}

/** Cause and action in one chrono kind: four bits of action, the cause above them. */
internal object ChronoKinds {
    private const val ACTION_BITS = 4

    init {
        check(ActionKind.entries.size <= 1 shl ACTION_BITS) { "${ActionKind.entries.size} actions outgrew $ACTION_BITS bits." }
    }

    fun of(cause: CauseKind, action: ActionKind): Int = (cause.ordinal shl ACTION_BITS) or action.ordinal

    fun cause(kind: Int): CauseKind = CauseKind.entries[kind ushr ACTION_BITS]

    fun action(kind: Int): ActionKind = ActionKind.entries[kind and ((1 shl ACTION_BITS) - 1)]
}
