package com.tracel.plugin.rollback.structure.fluid

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `a restore that empties nothing settles everything as before`() {
        assertTrue(wakes(Triple(0, 0, 0), emptySet()))
        assertTrue(wakes(Triple(5, 9, -3), emptySet()))
    }
}
