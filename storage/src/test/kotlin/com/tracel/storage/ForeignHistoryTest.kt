package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.world.BlockEdit
import com.tracel.engine.world.BlockEdits
import com.tracel.model.event.EventKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Records
import com.tracel.storage.ports.event.EventLog
import com.tracel.storage.ports.ops.*
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class ForeignHistoryTest {
    private val world = WorldId(UUID(0L, 1L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val dirt = BlockShape(BlockDataKey("minecraft:dirt"))
    private val here = BlockPos(world, 10, 64, 10)

    private fun Stack.foreign() = ForeignHistory(storage, worldLog, log, EventLog(storage), counters)

    private fun broke(at: BlockPos, millis: Long, before: BlockShape = stone) = ForeignRecord.Blocks(
        BlockEdits(
            ActionKind.BLOCK_BREAK,
            CauseKind.PLAYER_ACTION,
            player(1),
            millis,
            listOf(BlockEdit(at, before, BlockShape.AIR))
        ),
    )

    private suspend fun Stack.own(at: BlockPos, millis: Long, before: BlockShape, after: BlockShape) = worldLog.append(
        WorldChange(
            counters.nextSeq(), ActionKind.BLOCK_PLACE, CauseKind.PLAYER_ACTION, player(2), millis, at,
            ChangeSubject.Block(before, after),
        ),
    )

    @Test
    fun `imported history reads as older than our own at the same block`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.own(here, 5_000, BlockShape.AIR, dirt)
            val foreign = stack.foreign()
            assertEquals(5_000L, foreign.room(7).ownSince)
            assertEquals(
                Counters.SEQ_BASE,
                foreign.importedBelow(),
                "a rollback leaves alone whatever is numbered below this"
            )

            assertEquals(1, foreign.append(7, listOf(40L), listOf(broke(here, 1_000))))

            val history = stack.worldLog.at(here, 10)
            assertEquals(listOf(5_000L, 1_000L), history.map { it.epochMillis }, "newest first, ours on top")
            assertTrue(history.last().seq < Seq(Counters.SEQ_BASE))
            assertTrue(history.first().seq >= Seq(Counters.SEQ_BASE))
        }
    }

    @Test
    fun `the mark moves with what was written and survives a restart`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val foreign = stack.foreign()
            assertNull(foreign.room(7).mark)
            foreign.append(7, listOf(40L), listOf(broke(here, 1_000), broke(here.copy(x = 11), 1_000)))
            foreign.append(7, listOf(90L), listOf(broke(here.copy(x = 12), 2_000)))
            assertNull(foreign.room(8).mark, "another source has its own mark")
        }
        Stack(dir).use { stack ->
            assertEquals(ImportMark(rows = listOf(90L), records = 3), stack.foreign().room(7).mark)
            stack.foreign().append(7, listOf(91L), listOf(broke(here.copy(x = 13), 3_000)))
            val seqs = (10..13).map { x -> stack.worldLog.at(here.copy(x = x), 1).single().seq.raw }
            assertEquals(seqs.sorted(), seqs, "a later batch is numbered after an earlier one")
            assertEquals(4, seqs.distinct().size)
        }
    }

    @Test
    fun `a blast is filed as one section and an entity beside it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val blast = BlockEdits(
                ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, null, 1_000,
                (0 until 8).map { BlockEdit(here.copy(x = it), stone, BlockShape.AIR) },
            )
            val pig = UUID(9L, 9L)
            val type = EntityTypeKey("minecraft:pig")
            val killed = ForeignRecord.Change(
                ActionKind.ENTITY_REMOVE, CauseKind.PLAYER_ACTION, player(1), 1_500, here,
                ChangeSubject.Entity(pig, type, EntityShape(type, 10.5, 64.0, 10.5), null),
            )
            assertEquals(9, stack.foreign().append(7, listOf(10L), listOf(ForeignRecord.Blocks(blast), killed)))
            assertEquals(1, stack.worldLog.at(here.copy(x = 3), 5).size)
            assertEquals(ActionKind.ENTITY_REMOVE, stack.worldLog.at(here, 5).first().action)
        }
    }

    @Test
    fun `a database numbered from one has no room until it is empty`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.write { putPinned(Keys.counter(Counters.SEQ), Records.long(300)) }
            stack.worldLog.append(
                WorldChange(
                    Seq(5),
                    ActionKind.BLOCK_BREAK,
                    CauseKind.PLAYER_ACTION,
                    player(1),
                    1_000,
                    here,
                    ChangeSubject.Block(stone, BlockShape.AIR)
                ),
            )
            assertThrows<NoRoomForImport> { stack.foreign().room(7) }
        }
    }

    @Test
    fun `an old but empty database is moved up and imports go above what it had used`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.write { putPinned(Keys.counter(Counters.SEQ), Records.long(300)) }
            val foreign = stack.foreign()
            assertNull(foreign.room(7).ownSince)
            assertTrue(stack.counters.nextSeq().raw >= Counters.SEQ_BASE)
            foreign.append(7, listOf(1L), listOf(broke(here, 1_000)))
            assertEquals(300L, stack.worldLog.at(here, 1).single().seq.raw)
        }
    }

    @Test
    fun `items and words go to their own logs, numbered with the rest`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = HolderId.Block(world, 10, 64, 10)
            val put = ForeignRecord.Moved(
                CauseKind.PLAYER_ACTION, player(1), 1_000, here,
                listOf(Flow(ItemKey("DIAMOND"), Quantity(3), player(1), chest, FlowKind.MOVE)),
            )
            val said = ForeignRecord.Happened(EventKind.CHAT, player(1), 2_000, here, "hello")
            val left = ForeignRecord.Happened(EventKind.QUIT, player(1), 3_000, null, null)
            val before = stack.counters.nextTxnId().raw
            assertEquals(3, stack.foreign().append(7, listOf(1L, 2L, 3L), listOf(put, said, left)))
            assertEquals(ImportMark(rows = listOf(1L, 2L, 3L), records = 3), stack.foreign().room(7).mark)

            val moved = stack.log.query(LookupFilter(holders = setOf(chest))).single()
            assertEquals(3L, moved.flows.single().quantity.raw)
            assertTrue(stack.log.lotsAt(moved.seq).isEmpty(), "nothing a rollback could take hold of")
            assertTrue(moved.id.raw > before)
            assertTrue(
                (1..600).none { stack.counters.nextTxnId() == moved.id },
                "the allocator never hands the import's id out again"
            )

            val events = EventLog(stack.storage)
            val all = events.query(LookupFilter(), EventKind.entries.toSet())
            assertEquals(listOf(EventKind.QUIT, EventKind.CHAT), all.map { it.kind })
            assertEquals("hello", all.last().text)
            assertNull(all.first().at)
            assertEquals(
                1,
                events.query(LookupFilter(holders = setOf(player(1)), until = 2_500), EventKind.entries.toSet()).size
            )
            assertTrue(events.query(LookupFilter(holders = setOf(player(2))), EventKind.entries.toSet()).isEmpty())
            assertTrue(events.query(LookupFilter(), setOf(EventKind.COMMAND)).isEmpty())

            val purged = purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.EVENTS), PurgeFilter(before = 2_500)))
            assertEquals(1, purged.matched)
            assertEquals(
                listOf(EventKind.QUIT),
                events.query(LookupFilter(), EventKind.entries.toSet()).map { it.kind })
        }
    }

    @Test
    fun `purging everything makes room, and what is recorded right after does not take it back`(@TempDir dir: Path) =
        runTest {
            Stack(dir).use { stack ->
                stack.storage.write { putPinned(Keys.counter(Counters.SEQ), Records.long(300)) }
                stack.own(here, 1_000, BlockShape.AIR, dirt)
                assertThrows<NoRoomForImport> { stack.foreign().room(7) }
                assertEquals(0L, stack.foreign().importedBelow(), "numbered from one, so what is down there is its own")

                purgeAll(stack.storage)
                stack.own(here, 9_000, BlockShape.AIR, stone)

                assertEquals(9_000L, stack.foreign().room(7).ownSince)
                assertEquals(Counters.SEQ_BASE, stack.foreign().importedBelow())
                stack.foreign().append(7, listOf(1L), listOf(broke(here, 2_000)))
                val history = stack.worldLog.at(here, 10)
                assertEquals(listOf(9_000L, 2_000L), history.map { it.epochMillis })
                assertTrue(history.first().seq.raw >= Counters.SEQ_BASE)
                assertTrue(history.last().seq.raw >= 300, "above every number the old history could have used")
            }
        }
}
