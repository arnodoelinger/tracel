package com.tracel.plugin.rollback.structure.fluid

import com.tracel.plugin.util.packed
import org.bukkit.Material
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettleFluidsTest {
    private val cardinal = listOf(
        Triple(1, 0, 0), Triple(-1, 0, 0),
        Triple(0, 1, 0), Triple(0, -1, 0),
        Triple(0, 0, 1), Triple(0, 0, -1),
    )

    private fun wakes(cell: Triple<Int, Int, Int>, emptied: Set<Triple<Int, Int, Int>>): Boolean {
        if (cell in emptied) return false
        return cardinal.none { (dx, dy, dz) ->
            Triple(cell.first + dx, cell.second + dy, cell.third + dz) in emptied
        }
    }

    @Test
    fun `a cell the restore emptied is never woken`() {
        val hole = Triple(0, 0, 0)
        assertFalse(wakes(hole, setOf(hole)))
    }

    @Test
    fun `nor is the water sitting against it`() {
        val hole = Triple(0, 0, 0)
        for ((dx, dy, dz) in cardinal) {
            assertFalse(wakes(Triple(dx, dy, dz), setOf(hole)), "the rim at $dx,$dy,$dz stays put")
        }
    }

    @Test
    fun `water two cells out is somebody else's problem and settles normally`() {
        val hole = Triple(0, 0, 0)
        assertTrue(wakes(Triple(2, 0, 0), setOf(hole)))
        assertTrue(wakes(Triple(1, 1, 0), setOf(hole)), "diagonals do not touch the hole")
    }

    @Test
    fun `flowing water drains from the source outward`() {
        val source = packed(0, 64, 0)
        val mid = packed(1, 64, 0)
        val tail = packed(2, 64, 0)
        val flows = mapOf(
            source to Flow(Material.WATER, 0),
            mid to Flow(Material.WATER, 1),
            tail to Flow(Material.WATER, 2),
        )
        assertEquals(listOf(source, mid, tail), drainOrder(listOf(tail, mid, source), flows))
    }

    @Test
    fun `water still fed by a source outside the spill stays`() {
        val outside = packed(0, 64, 0)
        val edge = packed(1, 64, 0)
        val flows = mapOf(
            outside to Flow(Material.WATER, 0),
            edge to Flow(Material.WATER, 1),
        )
        assertEquals(emptyList<Long>(), drainOrder(listOf(edge), flows))
    }

    @Test
    fun `a restore that empties nothing settles everything as before`() {
        assertTrue(wakes(Triple(0, 0, 0), emptySet()))
        assertTrue(wakes(Triple(5, 9, -3), emptySet()))
    }
}
