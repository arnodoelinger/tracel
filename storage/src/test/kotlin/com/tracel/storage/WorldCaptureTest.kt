package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.world.BlockEdit
import com.tracel.model.id.Quantity
import com.tracel.model.id.WorldId
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.ActionKind
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class WorldCaptureTest {
    private val world = WorldId(UUID(0L, 1L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val steve = player(1)

    private fun broke(x: Int, y: Int, z: Int) = BlockEdit(BlockPos(world, x, y, z), stone, BlockShape.AIR)

    @Test
    fun `a block edit crosses the ring and lands in the world log`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val at = BlockPos(world, 10, 70, -3)
            assertTrue(
                stack.gate.blocks(
                    CauseKind.PLAYER_ACTION, ActionKind.BLOCK_BREAK, steve, 1_700_000_000_000L, world,
                    listOf(BlockEdit(at, stone, BlockShape.AIR)),
                )
            )
            stack.drain()

            val change = stack.worldLog.at(at).single()
            assertEquals(ActionKind.BLOCK_BREAK, change.action)
            assertEquals(CauseKind.PLAYER_ACTION, change.cause)
            assertEquals(steve, change.causedBy)
            assertEquals(1_700_000_000_000L, change.epochMillis)
            assertEquals(at, change.at)
        }
    }

    @Test
    fun `one explosion is one header and forty records, each at its own coordinate`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val edits = (0 until 40).map { broke(it, 70, 0) }
            assertTrue(
                stack.gate.blocks(CauseKind.EXPLOSION, ActionKind.BLOCK_BREAK, null, 1L, world, edits)
            )
            stack.drain()

            assertEquals(40, stack.worldLog.query(LookupFilter(limit = 100)).size)
            assertEquals(1, stack.worldLog.at(BlockPos(world, 7, 70, 0)).size)
        }
    }

    @Test
    fun `a block that did not actually change is not written down`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.gate.blocks(
                CauseKind.WORLD, ActionKind.BLOCK_CHANGE, null, 1L, world,
                listOf(BlockEdit(BlockPos(world, 1, 1, 1), stone, stone)),
            )
            stack.drain()

            assertTrue(stack.worldLog.query(LookupFilter()).isEmpty())
        }
    }

    @Test
    fun `an edit carrying extras is refused by the ring so the caller takes the slow path`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                val sign = BlockShape(BlockDataKey("minecraft:oak_sign"), BlockExtras.Opaque(byteArrayOf(1, 2, 3)))
                assertFalse(
                    stack.gate.blocks(
                        CauseKind.PLAYER_ACTION, ActionKind.BLOCK_BREAK, steve, 1L, world,
                        listOf(BlockEdit(BlockPos(world, 0, 0, 0), sign, BlockShape.AIR)),
                    ),
                    "a variable-length shape does not fit a 24-byte slot and must say so",
                )
                stack.drain()
                assertTrue(stack.worldLog.query(LookupFilter()).isEmpty())
            }
        }

    @Test
    fun `a block edit and an item movement drain together and stay in their own logs`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val at = BlockPos(world, 4, 64, 4)
            stack.ledger.mint(block(4, 64, 4), diamond, Quantity(3), stack.counters.nextTxnId())

            stack.gate.blocks(
                CauseKind.BLOCK_BREAK, ActionKind.BLOCK_BREAK, steve, 10L, world,
                listOf(BlockEdit(at, BlockShape(BlockDataKey("minecraft:chest[facing=north]")), BlockShape.AIR)),
            )
            stack.gate.move(CauseKind.BLOCK_BREAK, steve, 11L, diamond, block(4, 64, 4), steve, 3)
            stack.drain()

            assertEquals(1, stack.worldLog.at(at).size, "the shape change is in the world log")
            assertEquals(1, stack.log.query(LookupFilter(holders = setOf(steve))).size, "the material is in the other")
            assertEquals(3L, stack.ledger.totalAt(steve, diamond)?.raw)
        }
    }
}
