package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.TestShapes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class SectionDeltaLogTest {
    companion object {
        private const val SECTION_FULL = 4096
        private const val SECTION_RING = 2048
    }

    private val world = WorldId(UUID(0L, 1L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val dirt = BlockShape(BlockDataKey("minecraft:dirt"))
    private val steve = player(1)

    private fun section(count: Int, shape: (Int) -> BlockShape = { stone }): List<BlockEdit> =
        (0 until count).map { i ->
            BlockEdit(BlockPos(world, i and 15, 64 + (i shr 8), (i shr 4) and 15), shape(i), TestShapes.AIR)
        }

    private suspend fun blast(stack: Stack, edits: List<BlockEdit>) {
        assertTrue(
            stack.gate.blocks(CauseKind.EXPLOSION, ActionKind.BLOCK_BREAK, steve, 1_700_000_000_000L, world, edits),
            "the ring refused ${edits.size} edits",
        )
        stack.drain()
    }

    private suspend fun blastDirect(stack: Stack, edits: List<BlockEdit>) {
        stack.worldCapture.record(
            BlockEdits(ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 1_700_000_000_000L, edits)
        )
    }

    @Test
    fun `a full section round-trips every block it swallowed`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val edits = section(SECTION_FULL)
            blastDirect(stack, edits)

            val read = stack.worldLog.query(LookupFilter(limit = Int.MAX_VALUE))
            assertEquals(SECTION_FULL, read.size)
            assertEquals(edits.map { it.at }.toSet(), read.map { it.at }.toSet())
            for ((_, _, cause, causedBy, epochMillis, _, subject1) in read) {
                assertEquals(CauseKind.EXPLOSION, cause)
                assertEquals(steve, causedBy)
                assertEquals(1_700_000_000_000L, epochMillis)
                val subject = subject1 as ChangeSubject.Block
                assertEquals(stone, subject.before)
                assertEquals(TestShapes.AIR, subject.after)
            }
            assertEquals(SECTION_FULL, read.map { it.seq.raw }.toSet().size)
        }
    }

    @Test
    fun `a region of ten blocks inside a blasted section returns those ten`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val wanted = LookupRegion(
                world, minTileX = 0, maxTileX = 0, minTileZ = 0, maxTileZ = 0,
                minX = 3, maxX = 12, minY = 64, maxY = 64, minZ = 5, maxZ = 5,
            )
            val read = stack.worldLog.query(LookupFilter(region = wanted, limit = Int.MAX_VALUE))

            assertEquals(10, read.size, "the region asked for ten blocks, not the section they live in")
            assertEquals(
                (3..12).map { BlockPos(world, it, 64, 5) }.toSet(),
                read.map { it.at }.toSet(),
            )
        }
    }

    @Test
    fun `a region of one block returns one block`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val here = LookupRegion(
                world, 0, 0, 0, 0,
                minX = 7, maxX = 7, minY = 65, maxY = 65, minZ = 9, maxZ = 9,
            )
            val read = stack.worldLog.query(LookupFilter(region = here, limit = Int.MAX_VALUE))
            assertEquals(listOf(BlockPos(world, 7, 65, 9)), read.map { it.at })
        }
    }

    @Test
    fun `a region touching no part of the section returns nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val elsewhere = LookupRegion(
                world, minTileX = 40, maxTileX = 41, minTileZ = 40, maxTileZ = 41,
            )
            assertEquals(
                emptyList<BlockPos>(),
                stack.worldLog.query(LookupFilter(region = elsewhere, limit = Int.MAX_VALUE)).map { it.at },
            )
        }
    }

    @Test
    fun `a material filter picks its blocks out of a mixed section`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blastDirect(stack, section(SECTION_FULL) { if (it % 4 == 0) dirt else stone })

            val read = stack.worldLog.query(
                LookupFilter(material = "minecraft:dirt", limit = Int.MAX_VALUE)
            )
            assertEquals(SECTION_FULL / 4, read.size, "every fourth block was dirt")
            for ((_, _, _, _, _, _, subject) in read) {
                assertEquals(dirt, (subject as ChangeSubject.Block).before)
            }
        }
    }

    @Test
    fun `the history of one coordinate finds it inside the delta`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val at = BlockPos(world, 11, 66, 3)
            val history = stack.worldLog.at(at)
            assertEquals(1, history.size)
            assertEquals(at, history.single().at)
            assertEquals(stone, (history.single().subject as ChangeSubject.Block).before)

            assertEquals(emptyList<Any>(), stack.worldLog.at(BlockPos(world, 11, 79, 3)))
        }
    }

    @Test
    fun `a coordinate touched by both a delta and a single edit sees both, newest first`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val at = BlockPos(world, 2, 64, 2)
            blast(stack, section(SECTION_RING))
            assertTrue(
                stack.gate.blocks(
                    CauseKind.PLAYER_ACTION, ActionKind.BLOCK_CHANGE, steve, 1_700_000_001_000L, world,
                    listOf(BlockEdit(at, TestShapes.AIR, dirt)),
                )
            )
            stack.drain()

            val history = stack.worldLog.at(at)
            assertEquals(2, history.size, "the delta and the single edit both happened here")
            assertTrue(
                history[0].seq.raw > history[1].seq.raw,
                "history reads newest first however the two families were merged",
            )
            assertEquals(dirt, (history[0].subject as ChangeSubject.Block).after)
            assertEquals(TestShapes.AIR, (history[1].subject as ChangeSubject.Block).after)
        }
    }

    @Test
    fun `tile entity contents survive being packed into a delta`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chestAt = BlockPos(world, 6, 64, 6)
            val chest = BlockShape(BlockDataKey("minecraft:chest[facing=north]"), BlockExtras.Opaque(byteArrayOf(4, 2)))
            val edits =
                section(SECTION_FULL).map { if (it.at == chestAt) BlockEdit(it.at, chest, TestShapes.AIR) else it }
            blastDirect(stack, edits)

            val change = stack.worldLog.at(chestAt).single()
            assertEquals(chest, (change.subject as ChangeSubject.Block).before)
            val plain = stack.worldLog.at(BlockPos(world, 7, 64, 6)).single()
            assertEquals(stone, (plain.subject as ChangeSubject.Block).before)
        }
    }

    @Test
    fun `a single-block event is still written and read one at a time`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val edits = section(1)
            blast(stack, edits)

            val read = stack.worldLog.query(LookupFilter(limit = Int.MAX_VALUE))
            assertEquals(1, read.size)
            assertEquals(edits.map { it.at }.toSet(), read.map { it.at }.toSet())
            assertEquals(1, stack.worldLog.at(edits.first().at).size)
        }
    }

    @Test
    fun `two blocks in one section already share a record`(@TempDir dir: Path) = runTest {
        Stack(dir).use {
            val pair = dir.resolve("pair")
            val apart = dir.resolve("apart")
            Stack(pair).use { blastDirect(it, section(2)) }
            Stack(apart).use { stack ->
                for (edit in section(2)) blastDirect(stack, listOf(edit))
            }

            assertTrue(
                bytesIn(pair) < bytesIn(apart),
                "two blocks of one event must cost less together than apart: " +
                        "${bytesIn(pair)} against ${bytesIn(apart)}",
            )

            Stack(dir.resolve("read")).use { stack ->
                val edits = section(2)
                blastDirect(stack, edits)
                val read = stack.worldLog.query(LookupFilter(limit = Int.MAX_VALUE))
                assertEquals(edits.map { it.at }.toSet(), read.map { it.at }.toSet())
                assertEquals(2, read.map { it.seq.raw }.toSet().size)
            }
        }
    }

    @Test
    fun `an event spanning sections is split into one record for each`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val edits = (0 until 3).flatMap { s ->
                (0 until 20).map { i ->
                    BlockEdit(
                        BlockPos(world, i and 15, 64 + s * 16, i shr 4),
                        stone,
                        TestShapes.AIR
                    )
                }
            }
            blast(stack, edits)

            val read = stack.worldLog.query(LookupFilter(limit = Int.MAX_VALUE))
            assertEquals(60, read.size)
            assertEquals(edits.map { it.at }.toSet(), read.map { it.at }.toSet())
            assertEquals(60, read.map { it.seq.raw }.toSet().size, "sections must not hand out the same sequences")
        }
    }

    @Test
    fun `a section delta costs a fraction of what the same blocks cost apiece`(@TempDir dir: Path) = runTest {
        val grouped = dir.resolve("grouped")
        val apiece = dir.resolve("apiece")
        Stack(grouped).use { blastDirect(it, section(SECTION_FULL)) }
        Stack(apiece).use { stack ->
            for (edit in section(SECTION_FULL)) blastDirect(stack, listOf(edit))
        }

        val groupedBytes = bytesIn(grouped)
        val apieceBytes = bytesIn(apiece)
        assertTrue(
            apieceBytes > groupedBytes * 10,
            "grouping saved too little to be worth its complexity: $apieceBytes apiece against $groupedBytes grouped",
        )
    }

    private suspend fun rollbackView(stack: Stack, region: LookupRegion, structureEnds: Boolean = true) =
        stack.worldLog.queryTogether(
            stack.log,
            LookupFilter(region = region, limit = Int.MAX_VALUE),
            includeWorld = true,
            structureEnds = structureEnds,
        ).first

    @Test
    fun `the rollback read path expands a delta instead of choking on it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val whole = LookupRegion(world, 0, 0, 0, 0)
            val read = rollbackView(stack, whole)

            assertEquals(SECTION_RING, read.size, "every blasted block has to come back to be put back")
            assertEquals(section(SECTION_RING).map { it.at }.toSet(), read.map { it.at }.toSet())
        }
    }

    @Test
    fun `the rollback read path honours a region of ten blocks inside a delta`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            blast(stack, section(SECTION_RING))

            val ten = LookupRegion(
                world, 0, 0, 0, 0,
                minX = 3, maxX = 12, minY = 64, maxY = 64, minZ = 5, maxZ = 5,
            )
            val read = rollbackView(stack, ten)

            assertEquals(10, read.size, "a rollback of ten blocks must not put back the whole section")
            assertEquals((3..12).map { BlockPos(world, it, 64, 5) }.toSet(), read.map { it.at }.toSet())
        }
    }

    @Test
    fun `the rollback read path keeps both ends of a coordinate touched twice`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val at = BlockPos(world, 2, 64, 2)
            blast(stack, section(SECTION_RING))
            assertTrue(
                stack.gate.blocks(
                    CauseKind.PLAYER_ACTION, ActionKind.BLOCK_CHANGE, steve, 1_700_000_001_000L, world,
                    listOf(BlockEdit(TestShapes.AIR.let { _ -> at }, TestShapes.AIR, dirt)),
                )
            )
            stack.drain()

            val here = LookupRegion(world, 0, 0, 0, 0, minX = 2, maxX = 2, minY = 64, maxY = 64, minZ = 2, maxZ = 2)
            val read = rollbackView(stack, here)

            assertEquals(2, read.size)
            assertEquals(dirt, (read.first().subject as ChangeSubject.Block).after)
            assertEquals(stone, (read.last().subject as ChangeSubject.Block).before)
        }
    }


    @Test
    fun `a rollback scoped to one player does not take in another player's blast`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val alex = player(2)
            assertTrue(
                stack.gate.blocks(
                    CauseKind.EXPLOSION, ActionKind.BLOCK_BREAK, steve, 1_700_000_000_000L, world,
                    (0 until 64).map {
                        BlockEdit(
                            BlockPos(world, it and 15, 64 + (it shr 4), 0),
                            stone,
                            TestShapes.AIR
                        )
                    },
                )
            )
            assertTrue(
                stack.gate.blocks(
                    CauseKind.EXPLOSION, ActionKind.BLOCK_BREAK, alex, 1_700_000_001_000L, world,
                    (0 until 64).map {
                        BlockEdit(
                            BlockPos(world, it and 15, 64 + (it shr 4), 1),
                            stone,
                            TestShapes.AIR
                        )
                    },
                )
            )
            stack.drain()

            val whole = LookupRegion(world, 0, 0, 0, 0)
            val mine = stack.worldLog.queryTogether(
                stack.log,
                LookupFilter(region = whole, holders = setOf(steve), limit = Int.MAX_VALUE),
                includeWorld = true,
                structureEnds = true,
            ).first

            assertEquals(64, mine.size, "only one of the two blasts was this player's")
            assertTrue(mine.all { it.at.z == 0 }, "the other player's blast is at z = 1 and must not be here")
            assertTrue(mine.all { it.causedBy == steve })

            val notMine = stack.worldLog.queryTogether(
                stack.log,
                LookupFilter(region = whole, excludedHolders = setOf(steve), limit = Int.MAX_VALUE),
                includeWorld = true,
                structureEnds = true,
            ).first
            assertEquals(64, notMine.size)
            assertTrue(notMine.all { it.causedBy == alex })
        }
    }

    private fun bytesIn(dir: Path): Long =
        Files.walk(dir).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .mapToLong { Files.size(it) }
                .sum()
        }
}
