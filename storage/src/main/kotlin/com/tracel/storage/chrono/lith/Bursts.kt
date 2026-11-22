package com.tracel.storage.chrono.lith

import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.Tile
import com.tracel.storage.codec.Tag
import java.lang.foreign.ValueLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A point something spread out from at one moment, in world coordinates. */
class Burst(val x: Double, val y: Double, val z: Double)

/** The bursts of one moment, for an encoder that wants to know where a change came from. */
fun interface Bursts {
    fun at(millis: Long): List<Burst>

    companion object {
        val NONE: Bursts = Bursts { emptyList() }

        fun key(world: Int, millis: Long, burst: Burst): ByteArray =
            Tag.CHRONO_BURST[world, millis, burst.x.toBits() * 31 + burst.y.toBits() * 17 + burst.z.toBits()]

        fun value(burst: Burst): ByteArray =
            ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putDouble(burst.x).putDouble(burst.y)
                .putDouble(burst.z).array()

        fun of(unit: StorageUnit, tile: Tile, since: Long, until: Long): Bursts {
            val byMillis = HashMap<Long, MutableList<Burst>>()
            val prefix = Tag.CHRONO_BURST[tile.world]
            unit.scan(prefix, Tag.CHRONO_BURST[tile.world, maxOf(since, 0L)]).use { c ->
                while (c.next()) {
                    val millis = Tag.CHRONO_BURST.long(c, 1)
                    if (millis > until) break
                    val v = c.value()
                    val b = ByteBuffer.wrap(v.toArray(ValueLayout.JAVA_BYTE)).order(ByteOrder.LITTLE_ENDIAN)
                    byMillis.getOrPut(millis) { ArrayList(1) } += Burst(b.getDouble(), b.getDouble(), b.getDouble())
                }
            }
            if (byMillis.isEmpty()) return NONE
            return Bursts { byMillis[it].orEmpty() }
        }
    }
}
