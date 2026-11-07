package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Tile

/** A yes or no about one ID: an actor, a kind, a state. */
fun interface IdTest {
    /** Whether this test admits the given ID. */
    fun admits(id: Int): Boolean

    companion object {
        val ANY: IdTest = IdTest { true }

        fun of(ids: Collection<Int>): IdTest {
            val sorted = ids.toIntArray().also { it.sort() }
            return IdTest { sorted.binarySearch(it) >= 0 }
        }

        fun none(ids: Collection<Int>): IdTest {
            val sorted = ids.toIntArray().also { it.sort() }
            return IdTest { sorted.binarySearch(it) < 0 }
        }
    }
}

/** Both have to say yes. */
infix fun IdTest.and(other: IdTest): IdTest = IdTest { admits(it) && other.admits(it) }

/** A box of world coordinates, ends included. */
class Box(
    val minX: Int = Int.MIN_VALUE,
    val maxX: Int = Int.MAX_VALUE,
    val minY: Int = Int.MIN_VALUE,
    val maxY: Int = Int.MAX_VALUE,
    val minZ: Int = Int.MIN_VALUE,
    val maxZ: Int = Int.MAX_VALUE,
) {
    /** Whether the world coordinate ([x], [y], [z]) lies inside. */
    fun contains(x: Int, y: Int, z: Int): Boolean = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    /** Whether [cell] of [tile] lies inside. */
    fun contains(tile: Tile, cell: Cell): Boolean = contains(tile.originX + cell.x, cell.y, tile.originZ + cell.z)

    /** Whether the whole of [tile] lies inside, at every height: then no cell needs testing at all. */
    fun coversColumns(tile: Tile): Boolean =
        minY == Int.MIN_VALUE && maxY == Int.MAX_VALUE &&
                tile.originX >= minX && tile.originX + 15 <= maxX && tile.originZ >= minZ && tile.originZ + 15 <= maxZ

    companion object {
        val EVERYWHERE: Box = Box()
    }
}

/** Which changes a question is about. */
class Sieve(
    val since: Long = Long.MIN_VALUE,
    val until: Long = Long.MAX_VALUE,
    val actors: IdTest = IdTest.ANY,
    val kinds: IdTest = IdTest.ANY,
    val states: IdTest? = null,
    val box: Box = Box.EVERYWHERE,
) {
    /** Whether a change at [millis] is let through, and not filtered out by [since] or [until]. */
    fun admitsTime(millis: Long): Boolean = millis in since..until

    /** The same sieve, letting through only what [actor] did as well. */
    fun onlyBy(actor: Int): Sieve = Sieve(since, until, actors and IdTest { it == actor }, kinds, states, box)

    companion object {
        val ALL: Sieve = Sieve()
    }
}
