package com.tracel.plugin.rollback.structure.fluid

import com.tracel.plugin.util.AIR
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class FluidFreezeTest {
    private val world = WorldId(UUID(0L, 1L))
    private val elsewhere = WorldId(UUID(0L, 2L))
    private val water = BlockShape(BlockDataKey("minecraft:water[level=3]"))
    private val nobody: suspend (List<BlockPos>) -> Unit = {}

    private fun set(x: Int, y: Int, z: Int) = StructureStep.SetBlock(BlockPos(world, x, y, z), AIR, water)

    private val area = LookupRegion(world, 0, 1, 0, 1, minX = 0, maxX = 31, minZ = 0, maxZ = 31)

    @Test
    fun `nothing is held while no job runs`() {
        assertFalse(FluidFreeze().holds(world, 0, 64, 0))
    }

    @Test
    fun `a job holds exactly the cells it writes, for as long as it runs`() = runTest {
        val freeze = FluidFreeze()
        freeze.holding(listOf(set(0, 64, 0), set(-30_000_000, -64, 29_999_999)), nobody) {
            assertTrue(freeze.holds(world, 0, 64, 0))
            assertTrue(freeze.holds(world, -30_000_000, -64, 29_999_999))
            assertFalse(freeze.holds(world, 1, 64, 0), "the cell beside it flows as it likes")
            assertFalse(freeze.holds(elsewhere, 0, 64, 0), "and the same spot in another world is another cell")
        }
        assertFalse(freeze.holds(world, 0, 64, 0), "let go once the job is done")
    }

    @Test
    fun `a job that fails lets go all the same`() = runTest {
        val freeze = FluidFreeze()
        val failed = runCatching { freeze.holding(listOf(set(0, 64, 0)), nobody) { error("the ledger refused") } }
        assertTrue(failed.isFailure)
        assertFalse(freeze.holds(world, 0, 64, 0))
    }

    @Test
    fun `two jobs hold side by side and each lets go of its own`() = runTest {
        val freeze = FluidFreeze()
        freeze.holding(listOf(set(0, 64, 0)), nobody) {
            freeze.holding(listOf(set(5, 64, 5)), nobody) {
                assertTrue(freeze.holds(world, 0, 64, 0))
                assertTrue(freeze.holds(world, 5, 64, 5))
            }
            assertTrue(freeze.holds(world, 0, 64, 0), "the first is still writing")
            assertFalse(freeze.holds(world, 5, 64, 5))
        }
    }

    @Test
    fun `entity steps hold no cell`() = runTest {
        val freeze = FluidFreeze()
        val boat = EntityShape(EntityTypeKey("minecraft:oak_boat"), 0.5, 64.0, 0.5)
        val hull = StructureStep.RemoveEntity(BlockPos(world, 0, 64, 0), UUID.randomUUID(), boat)
        freeze.holding(listOf(hull), nobody) { assertFalse(freeze.holds(world, 0, 64, 0)) }
    }

    @Test
    fun `an area holds every cell in it, top to bottom, until the rollback over it is done`() = runTest {
        val freeze = FluidFreeze()
        freeze.holdingArea(area, nobody) {
            assertTrue(freeze.holds(world, 0, -64, 0))
            assertTrue(freeze.holds(world, 31, 319, 31))
            assertFalse(freeze.holds(world, 32, 64, 0), "the flood beyond the edge is not ours")
            assertFalse(freeze.holds(elsewhere, 5, 64, 5))
        }
        assertFalse(freeze.holds(world, 5, 64, 5))
    }

    @Test
    fun `a rollback without an area holds nothing beyond its own cells`() = runTest {
        val freeze = FluidFreeze()
        freeze.holdingArea(null, nobody) { assertFalse(freeze.holds(world, 5, 64, 5)) }
    }

    @Test
    fun `what the freeze stopped is woken by whoever lets go of it last`() = runTest {
        val freeze = FluidFreeze()
        val woken = ArrayList<BlockPos>()
        val wake: suspend (List<BlockPos>) -> Unit = { woken += it }
        val river = BlockPos(world, 20, 64, 20)
        val outside = BlockPos(world, 40, 64, 5)
        freeze.holdingArea(area, wake) {
            freeze.holding(listOf(set(5, 64, 5)), wake) {
                freeze.stir(river.world, river.x, river.y, river.z)
                freeze.stir(outside.world, outside.x, outside.y, outside.z)
            }
            assertEquals(listOf(outside), woken, "the area still holds the river; the spring outside it may run")
        }
        assertEquals(listOf(outside, river), woken, "and the river, nowhere near the job, runs on once the area lets go")
    }

    @Test
    fun `water the job overwrote, or that was reaching for its hole, is left as the job put it`() = runTest {
        val freeze = FluidFreeze()
        val woken = ArrayList<BlockPos>()
        val wake: suspend (List<BlockPos>) -> Unit = { woken += it }
        freeze.holdingArea(area, wake) {
            freeze.holding(listOf(set(5, 64, 5)), wake) {
                freeze.stir(world, 5, 64, 5)
                freeze.stir(world, 5, 65, 5)
            }
        }
        assertEquals(emptyList<BlockPos>(), woken)
    }

    @Test
    fun `a rollback lets go of its area as soon as its last write is in, and only once`() = runTest {
        val freeze = FluidFreeze()
        val woken = ArrayList<BlockPos>()
        val wake: suspend (List<BlockPos>) -> Unit = { woken += it }
        freeze.holdingArea(area, wake) {
            freeze.stir(world, 20, 64, 20)
            freeze.letGoOfArea()
            assertFalse(freeze.holds(world, 20, 64, 20), "the settling after it runs against a world that moves")
            assertEquals(1, woken.size)
        }
        assertEquals(1, woken.size, "nothing is woken twice")
    }
}
