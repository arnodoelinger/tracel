package com.tracel.storage.chrono.lith

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.Tile
import com.tracel.storage.codec.Tag
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One cube of one tile. */
class VoidMap(val millis: Long, val cubeY: Int, val bits: LongArray) {
    fun empty(y: Int, z: Int, x: Int): Boolean {
        val i = ((y shl 4) or z) shl 4 or x
        return (bits[i ushr 6] ushr (i and 63)) and 1L != 0L
    }
}

/** The empty cells a tile's capsule knows of. */
class Voids(val maps: List<VoidMap>) {
    private val byCube = HashMap<Int, VoidMap>(maps.size * 2).also { m ->
        for (v in maps) {
            val known = m[v.cubeY]
            if (known == null || v.millis < known.millis) m[v.cubeY] = v
        }
    }

    val isEmpty: Boolean get() = maps.isEmpty()

    /** Whether the cell at height [y], [z], [x] of the tile was empty before the capsule; `false` when unknown. */
    fun empty(y: Int, z: Int, x: Int): Boolean = byCube[Math.floorDiv(y, 16)]?.empty(y and 15, z, x) ?: false

    /** The maps of both, this one's first where both have a moment and cube. */
    operator fun plus(other: Voids): Voids {
        if (other.isEmpty) return this
        if (isEmpty) return other
        val seen = HashSet<Long>()
        val out = ArrayList<VoidMap>(maps.size + other.maps.size)
        for (v in maps + other.maps) if (seen.add(key(v.millis, v.cubeY))) out += v
        return Voids(out)
    }

    /** The earliest map of every cube between [loCube] and [hiCube] taken from [since] to [until]. */
    fun only(since: Long, until: Long, loCube: Int, hiCube: Int): Voids =
        Voids(byCube.values.filter { it.millis in since..until && it.cubeY in loCube..hiCube }.sortedBy { it.cubeY })

    private fun key(millis: Long, cubeY: Int): Long = millis * 4099 + cubeY

    companion object {
        val NONE: Voids = Voids(emptyList())

        private const val BYTES = 4096 / 8

        fun key(tile: Tile, millis: Long, cubeY: Int): ByteArray = Tag.CHRONO_VOID[tile.world, tile.x, tile.z, millis, cubeY]

        fun value(bits: LongArray): ByteArray {
            val b = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (l in bits) b.putLong(l)
            return b.array()
        }

        fun of(unit: StorageUnit, tile: Tile, since: Long, until: Long): Voids {
            val out = ArrayList<VoidMap>()
            val prefix = Tag.CHRONO_VOID[tile.world, tile.x, tile.z]
            unit.scan(prefix, Tag.CHRONO_VOID[tile.world, tile.x, tile.z, maxOf(since, 0L)]).use { c ->
                while (c.next()) {
                    val millis = Tag.CHRONO_VOID.long(c, 3)
                    if (millis > until) break
                    val v = ByteBuffer.wrap(c.value().toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN)
                    out += VoidMap(millis, Tag.CHRONO_VOID.int(c, 4), LongArray(BYTES / 8) { v.getLong() })
                }
            }
            return if (out.isEmpty()) NONE else Voids(out)
        }

        fun forget(unit: StorageUnit, tile: Tile, since: Long, until: Long) {
            val doomed = ArrayList<ByteArray>()
            val prefix = Tag.CHRONO_VOID[tile.world, tile.x, tile.z]
            unit.scan(prefix, Tag.CHRONO_VOID[tile.world, tile.x, tile.z, maxOf(since, 0L)]).use { c ->
                while (c.next()) {
                    if (Tag.CHRONO_VOID.long(c, 3) > until) break
                    doomed += c.key()
                }
            }
            for (k in doomed) unit.delete(k)
        }
    }
}
