package com.tracel.plugin.api.rollback

import com.tracel.api.rollback.RollbackError
import com.tracel.api.result.Res
import com.tracel.api.world.LookupArea
import java.util.UUID

/**
 * Areas plugins hold for themselves while they roll back. Two holds never overlap, and a plugin's rollback never
 * reaches into another's hold.
 *
 * Only API rollbacks look here; `/tracel rollback` has the one gate to keep it apart.
 */
internal class Borrows {
    private val held = ArrayList<Lease>()

    /** One hold. [expired] is set when its block ends, and everything made from it stops working. */
    class Lease(val plugin: String, val area: LookupArea) {
        @Volatile
        var expired: Boolean = false
            private set

        fun expire() {
            expired = true
        }
    }

    /** Holds [area] for [plugin], or names who holds part of it already. */
    @Synchronized
    fun take(plugin: String, area: LookupArea): Res<Lease, RollbackError> {
        held.firstOrNull { overlap(it.area, area) }?.let { return Res.Err(RollbackError.Borrowed(it.plugin)) }
        return Res.Ok(Lease(plugin, area).also { held += it })
    }

    /** Releases [lease]. */
    @Synchronized
    fun release(lease: Lease) {
        lease.expire()
        held.remove(lease)
    }

    /** Who other than [plugin] holds part of [area], or `null`. */
    @Synchronized
    fun heldAgainst(plugin: String, area: LookupArea): String? =
        held.firstOrNull { it.plugin != plugin && overlap(it.area, area) }?.plugin

    companion object {
        fun within(inner: LookupArea, outer: LookupArea): Boolean {
            val a = box(inner)
            val b = box(outer)
            return a.world == b.world &&
                    a.minX >= b.minX && a.maxX <= b.maxX &&
                    a.minY >= b.minY && a.maxY <= b.maxY &&
                    a.minZ >= b.minZ && a.maxZ <= b.maxZ
        }

        fun overlap(one: LookupArea, other: LookupArea): Boolean {
            val a = box(one)
            val b = box(other)
            return a.world == b.world &&
                    a.minX <= b.maxX && b.minX <= a.maxX &&
                    a.minY <= b.maxY && b.minY <= a.maxY &&
                    a.minZ <= b.maxZ && b.minZ <= a.maxZ
        }

        private class Box(
            val world: UUID,
            val minX: Int, val minY: Int, val minZ: Int,
            val maxX: Int, val maxY: Int, val maxZ: Int,
        )

        private fun box(area: LookupArea): Box = when (area) {
            is LookupArea.Around -> with(area.center) {
                val r = area.radius
                Box(world, x - r, y - r, z - r, x + r, y + r, z + r)
            }
            is LookupArea.Cube -> Box(area.world, area.minX, area.minY, area.minZ, area.maxX, area.maxY, area.maxZ)
            is LookupArea.World -> Box(
                area.world,
                Int.MIN_VALUE, Int.MIN_VALUE, Int.MIN_VALUE,
                Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE,
            )
        }
    }
}
