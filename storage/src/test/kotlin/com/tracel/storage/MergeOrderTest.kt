package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.model.id.WorldId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockDataKey
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class MergeOrderTest {
    private val world = WorldId(UUID(0L, 1L))

    @Test
    fun `a region scan returns every change exactly once, newest first`(@TempDir directory: Path) = runTest {
        Stack(directory).use { stack ->
            // Twelve chunks square, several changes each: enough cursors that the ordering is the
            // heap's opinion rather than an accident.
            for (chunkX in 0 until 12) {
                for (chunkZ in 0 until 12) {
                    repeat(3) { i ->
                        stack.worldCapture.record(
                            BlockEdits(
                                ActionKind.BLOCK_BREAK,
                                CauseKind.PLAYER_ACTION,
                                player(1),
                                1_000L + i,
                                listOf(
                                    BlockEdit(
                                        BlockPos(world, chunkX * 16, 64 + i, chunkZ * 16),
                                        BlockShape(BlockDataKey("minecraft:stone")),
                                        BlockShape.AIR,
                                    )
                                ),
                            )
                        )
                    }
                }
            }

            val found = stack.worldLog.query(
                LookupFilter(
                    region = LookupRegion(world, 0, 11, 0, 11),
                    limit = Int.MAX_VALUE,
                )
            )

            assertEquals(12 * 12 * 3, found.size, "every change in the region, and none of them twice")

            val seqs = found.map { it.seq.raw }
            assertEquals(seqs.sortedDescending(), seqs, "newest first, across every chunk at once")
            assertEquals(seqs.size, seqs.toSet().size, "and each sequence handed out exactly once")
        }
    }
}
