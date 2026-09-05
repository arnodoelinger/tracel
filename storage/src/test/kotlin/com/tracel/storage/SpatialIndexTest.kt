package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.*
import com.tracel.storage.ports.log.QueryProbe
import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class SpatialIndexTest {
    private val world = WorldId(UUID(0L, 3L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val griefer = HolderId.Player(UUID(0L, 1L))

    private fun change(seq: Long, at: Long, x: Int, y: Int, z: Int) = WorldChange(
        Seq(seq),
        ActionKind.BLOCK_BREAK,
        CauseKind.PLAYER_ACTION,
        griefer,
        at,
        BlockPos(world, x, y, z),
        ChangeSubject.Block(stone, BlockShape.AIR),
    )

    private fun cube(center: Int, radius: Int) = LookupRegion(
        world,
        minChunkX = (center - radius) shr 4, maxChunkX = (center + radius) shr 4,
        minChunkZ = (center - radius) shr 4, maxChunkZ = (center + radius) shr 4,
        minX = center - radius, maxX = center + radius,
        minY = 64 - radius, maxY = 64 + radius,
        minZ = center - radius, maxZ = center + radius,
    )

    @Test
    fun `a short window does not read the hours around it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            var seq = 1L
            // One chunk, one hour of churn, one edit a second.
            stack.storage.batched {
                for (i in 0 until 3_600) {
                    stack.worldLog.append(change(seq++, now - (3_600 - i) * 1_000L, 4, 64, 4))
                }
            }

            QueryProbe.reset()
            val matches = stack.worldLog.query(
                LookupFilter(since = now - 60_000L, region = cube(4, 8), limit = Int.MAX_VALUE),
            )
            val probe = QueryProbe.read()

            assertEquals(60, matches.size, "a minute of a one-per-second chunk")
            // The walk is allowed the row that told it to stop, and nothing like the hour around it
            assertTrue(
                probe.indexRows <= matches.size + 4,
                "read ${probe.indexRows} index rows for ${matches.size} matches — the window is not bounding the scan",
            )
        }
    }

    @Test
    fun `an empty region costs nothing even with years of history beside it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            var seq = 1L
            stack.storage.batched {
                for (i in 0 until 2_000) {
                    stack.worldLog.append(change(seq++, now - i * 1_000L, 4, 64, 4))
                }
            }

            QueryProbe.reset()
            // Far away, in chunks nothing ever touched.
            val matches = stack.worldLog.query(
                LookupFilter(since = now - 3_600_000L, region = cube(5_000, 8), limit = Int.MAX_VALUE),
            )
            assertEquals(emptyList<WorldChange>(), matches)
            assertEquals(0L, QueryProbe.read().indexRows, "empty chunks must not be walked")
        }
    }

    @Test
    fun `a bare before with no since still only reads its own chunks`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            var seq = 1L
            stack.storage.batched {
                for (i in 0 until 500) stack.worldLog.append(change(seq++, now - i * 1_000L, 4, 64, 4))
                for (i in 0 until 500) stack.worldLog.append(change(seq++, now - i * 1_000L, 900, 64, 900))
            }

            QueryProbe.reset()
            val matches = stack.worldLog.query(
                LookupFilter(until = now - 100_000L, region = cube(4, 8), limit = Int.MAX_VALUE),
            )
            val probe = QueryProbe.read()
            assertEquals(400, matches.size)
            assertTrue(probe.indexRows <= 404, "read ${probe.indexRows} rows — the other chunk leaked in")
        }
    }

    @Test
    fun `a column stops at the region's last chunk`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            var seq = 1L
            stack.storage.batched {
                for (i in 0 until 50) stack.worldLog.append(change(seq++, now - i * 1_000L, 4, 64, 4))
                for (i in 0 until 500) stack.worldLog.append(change(seq++, now - i * 1_000L, 4, 64, 200))
            }

            QueryProbe.reset()
            val matches = stack.worldLog.query(
                LookupFilter(since = now - 3_600_000L, region = cube(4, 8), limit = Int.MAX_VALUE),
            )
            assertEquals(50, matches.size)
            assertTrue(QueryProbe.read().indexRows <= 54, "the walk ran past maxChunkZ")
        }
    }

    @Test
    fun `a transaction between a player and an entity is findable by region`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            val frame = HolderId.Entity(UUID(0L, 42L))
            stack.log.append(
                Transaction(
                    TxnId(1), Seq(1), now, CauseKind.PLAYER_ACTION, griefer,
                    listOf(Flow(ItemKey("minecraft:diamond_sword"), Quantity(1), griefer, frame, FlowKind.MOVE)),
                    at = BlockPos(world, 4, 64, 4),
                ),
            )

            val found = stack.log.query(
                LookupFilter(since = now - 60_000L, region = cube(4, 8), limit = Int.MAX_VALUE),
            )
            assertEquals(1, found.size, "a frame's cargo move is not on the map — no scope: query can reach it")
        }
    }

    @Test
    fun `a positionless entity transaction is invisible to every region query`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val now = 1_800_000_000_000L
            val frame = HolderId.Entity(UUID(0L, 42L))
            stack.log.append(
                Transaction(
                    TxnId(1), Seq(1), now, CauseKind.PLAYER_ACTION, griefer,
                    listOf(Flow(ItemKey("minecraft:diamond_sword"), Quantity(1), griefer, frame, FlowKind.MOVE)),
                    at = null,
                ),
            )

            val byRegion = stack.log.query(
                LookupFilter(since = now - 60_000L, region = cube(4, 8), limit = Int.MAX_VALUE),
            )
            val byTime = stack.log.query(
                LookupFilter(since = now - 60_000L, limit = Int.MAX_VALUE),
            )
            assertEquals(1, byTime.size, "it is in the log")
            assertEquals(0, byRegion.size, "and this is why a scope: rollback never sees it")
        }
    }
}
