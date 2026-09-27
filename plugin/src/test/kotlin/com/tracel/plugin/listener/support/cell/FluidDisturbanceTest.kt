package com.tracel.plugin.listener.support.cell

import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FluidDisturbanceTest {
    private val world = WorldId(UUID(0L, 1L))
    private val other = WorldId(UUID(0L, 2L))

    private fun at(x: Int) = BlockPos(world, x, 64, 0)

    @BeforeEach
    fun clean() = FluidCell.forgetAll()

    @Test
    fun `nothing is disturbed until a restore says so`() {
        assertFalse(FluidCell.isDisturbed(at(0)))
    }

    @Test
    fun `the cells a restore wrote are disturbed`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        assertTrue(FluidCell.isDisturbed(at(0), now = 1L))
        assertFalse(FluidCell.isDisturbed(at(1), now = 1L), "and only those, to begin with")
    }

    @Test
    fun `the mark travels downstream as far as the water does`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        for (x in 0 until 200) FluidCell.onFlow(at(x), at(x + 1), now = 1L)
        assertTrue(FluidCell.isDisturbed(at(200), now = 1L), "two hundred blocks out, still ours")
    }

    @Test
    fun `the mark travels upstream into a lake that is draining into us`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        for (x in 0 until 60) FluidCell.onFlow(at(x + 1), at(x), now = 1L)
        assertTrue(FluidCell.isDisturbed(at(60), now = 1L), "sixty blocks upstream, still ours")
    }

    @Test
    fun `a bucket takes its cell back from the chain`() {
        FluidCell.disturb(listOf(at(0), at(1)), now = 0L)
        assertTrue(FluidCell.isDisturbed(at(0), now = 1L))

        FluidCell.claim(listOf(at(0)))

        assertFalse(FluidCell.isDisturbed(at(0), now = 1L), "the person owns this water now")
        assertTrue(FluidCell.isDisturbed(at(1), now = 1L), "and only that cell — the rest is still settling")
    }

    @Test
    fun `a claimed cell does not get re-marked by water flowing through it`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        FluidCell.claim(listOf(at(0)))
        FluidCell.onFlow(at(0), at(1), now = 1L)
        assertFalse(FluidCell.isDisturbed(at(1), now = 1L), "a claimed cell hands nothing on")
    }

    @Test
    fun `a cell emptying beside ours inherits the mark, with no flow edge to carry it`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        FluidCell.inherit(at(1), listOf(at(0), at(2)), now = 1L)
        assertTrue(FluidCell.isDisturbed(at(1), now = 1L), "its neighbour is ours, so it is")
    }

    @Test
    fun `inheriting walks a draining lake as far as it drains`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        for (x in 1..300) FluidCell.inherit(at(x), listOf(at(x - 1), at(x + 1)), now = 1L)
        assertTrue(FluidCell.isDisturbed(at(300), now = 1L))
    }

    @Test
    fun `a cell with no disturbed neighbour inherits nothing`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        FluidCell.inherit(at(50), listOf(at(49), at(51)), now = 1L)
        assertFalse(FluidCell.isDisturbed(at(50), now = 1L))
    }

    @Test
    fun `inheriting cannot outlive the deadline it inherits`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        val past = FluidCell.MAX_SETTLING_MILLIS + 1
        FluidCell.inherit(at(1), listOf(at(0)), now = past)
        assertFalse(FluidCell.isDisturbed(at(1), now = past))
    }

    @Test
    fun `water that never touches ours is nobody's business`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        FluidCell.onFlow(at(500), at(501), now = 1L)
        assertFalse(FluidCell.isDisturbed(at(500), now = 1L))
        assertFalse(FluidCell.isDisturbed(at(501), now = 1L))
    }

    @Test
    fun `a chain never crosses into another world`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        val elsewhere = BlockPos(other, 0, 64, 0)
        assertFalse(FluidCell.isDisturbed(elsewhere, now = 1L))
    }

    @Test
    fun `a chain that never stops still gives up on time`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        val past = FluidCell.MAX_SETTLING_MILLIS + 1
        FluidCell.onFlow(at(0), at(1), now = past)
        assertFalse(FluidCell.isDisturbed(at(0), now = past), "the seed has expired")
        assertFalse(FluidCell.isDisturbed(at(1), now = past), "so it hands nothing on")
    }

    @Test
    fun `a live chain keeps its own deadline rather than taking the newest`() {
        FluidCell.disturb(listOf(at(0)), now = 0L)
        FluidCell.onFlow(at(0), at(1), now = 1_000L)
        val nearDeadline = FluidCell.MAX_SETTLING_MILLIS - 1
        assertTrue(FluidCell.isDisturbed(at(1), now = nearDeadline), "still inside the seed's window")
        assertFalse(
            FluidCell.isDisturbed(at(1), now = FluidCell.MAX_SETTLING_MILLIS + 1),
            "and not one tick past it, however long the chain kept moving",
        )
    }
}
