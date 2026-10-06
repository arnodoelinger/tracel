package com.tracel.storage.ports.ops

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.PurgeFilter
import com.tracel.engine.store.PurgeReport
import com.tracel.engine.store.PurgeSpec
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.codec.History
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.TestShapes
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicLong

class PurgeSomeTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))
    private val stone = BlockShape(BlockDataKey("minecraft:stone"))
    private val steve = player(1)
    private val alex = player(2)
    private val day = LsmConfig.DAY_MILLIS
    private val start = 1_000 * day

    private val touched = listOf(
        Keys.TXN, Keys.TXN_BY_ID, Keys.ACTOR, Keys.ITEM, Keys.TIME, Keys.SPATIAL, Keys.TXN_LOT,
        Keys.WCHG, Keys.WCHG_AT, Keys.WCHG_AT_SECTION, Keys.WCHG_ENTITY, Keys.CONTAINER_SLOT, Keys.ACTOR_VISIT,
    )

    private fun broke(seq: Long, world: WorldId, by: HolderId, epoch: Long, x: Int = seq.toInt()) = WorldChange(
        Seq(seq), ActionKind.BLOCK_BREAK, CauseKind.PLAYER_ACTION, by, epoch,
        BlockPos(world, x, 70, 3), ChangeSubject.Block(stone, TestShapes.AIR),
    )

    private fun moved(seq: Long, world: WorldId, by: HolderId, epoch: Long) = Transaction(
        TxnId(seq), Seq(seq), epoch, CauseKind.PLAYER_ACTION, by,
        listOf(Flow(diamond, Quantity(2), HolderId.Block(world, 1, 64, 1), by, FlowKind.MOVE)),
        listOf(FlowLot(0, LotId(seq), Quantity(2))),
        at = BlockPos(world, 1, 64, 1),
    )

    private suspend fun fill(stack: Stack) {
        val boat = EntityTypeKey("minecraft:oak_boat")
        stack.worldLog.append(broke(501, overworld, steve, 100, x = 1))
        stack.worldLog.append(broke(502, nether, steve, 200, x = 2))
        stack.worldLog.append(broke(503, overworld, alex, 600, x = 3))
        stack.worldLog.append(
            WorldChange(
                Seq(504), ActionKind.ENTITY_SPAWN, CauseKind.PLAYER_ACTION, alex, 700,
                BlockPos(nether, 4, 70, 4),
                ChangeSubject.Entity(UUID(7, 7), boat, null, EntityShape(boat, 4.5, 70.0, 4.5)),
            )
        )
        stack.worldLog.append(
            WorldChange(
                Seq(505), ActionKind.BLOCK_BREAK, CauseKind.ROLLBACK, steve, 150,
                BlockPos(overworld, 9, 70, 9), ChangeSubject.Block(stone, TestShapes.AIR),
            )
        )
        stack.worldCapture.record(
            BlockEdits(
                ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, steve, 300,
                (0 until 40).map { BlockEdit(BlockPos(overworld, it and 15, 64, it shr 4), stone, TestShapes.AIR) },
            )
        )
        stack.log.append(moved(1001, overworld, steve, 120))
        stack.log.append(moved(1002, nether, steve, 220))
        stack.log.append(moved(1003, overworld, alex, 650))
        stack.containerSlots.record(HolderId.Block(overworld, 1, 64, 1), 130, listOf(ContainerSlotEntry(0, diamond, 1)))
        stack.containerSlots.record(HolderId.Block(nether, 1, 64, 1), 230, listOf(ContainerSlotEntry(0, diamond, 1)))
        stack.containerSlots.record(HolderId.Block(overworld, 1, 64, 1), 630, listOf(ContainerSlotEntry(1, diamond, 1)))
    }

    private suspend fun rows(stack: Stack): Map<Byte, Int> = stack.storage.read {
        touched.associateWith { tag ->
            var n = 0
            scan(Keys.tagPrefix(tag)).use { while (it.next()) n++ }
            n
        }
    }

    private suspend fun orphans(stack: Stack): List<String> = stack.storage.read {
        val live = HashSet<Long>()
        for (tag in listOf(Keys.TXN, Keys.WCHG)) {
            scan(Keys.tagPrefix(tag)).use { while (it.next()) live += KeyReader.u64(it.key(), 1) }
        }
        val lost = ArrayList<String>()
        for (tag in listOf(
            Keys.ACTOR,
            Keys.ITEM,
            Keys.TIME,
            Keys.SPATIAL,
            Keys.WCHG_AT,
            Keys.WCHG_AT_SECTION,
            Keys.WCHG_ENTITY
        )) {
            scan(Keys.tagPrefix(tag)).use {
                while (it.next()) {
                    val key = it.key()
                    val seq = Keys.invert(KeyReader.u64(key, key.size - 8))
                    if (seq !in live) lost += "${Keys.tagName(tag)} of seq $seq"
                }
            }
        }
        scan(Keys.tagPrefix(Keys.TXN_LOT)).use {
            while (it.next()) if (KeyReader.u64(it.key(), 1) !in live) lost += "txnLot of seq ${
                KeyReader.u64(
                    it.key(),
                    1
                )
            }"
        }
        lost
    }

    private suspend fun purgeAndCheck(stack: Stack, spec: PurgeSpec): PurgeReport {
        fill(stack)
        val before = rows(stack)
        val promised = previewPurge(stack.storage, spec)
        assertEquals(before, rows(stack), "a preview touches nothing")

        val report = purgeSome(stack.storage, spec)
        assertEquals(
            promised.tallies.mapValues { it.value.copy(bytes = 0) },
            report.tallies.mapValues { it.value.copy(bytes = 0) },
            "the preview says what the purge then does",
        )
        assertEquals(
            before.values.sum().toLong() - report.rows,
            rows(stack).values.sum().toLong(),
            "every row it counted is gone, and no other"
        )
        assertEquals(emptyList<String>(), orphans(stack), "an index row outlived its record")
        return report
    }

    @Test
    fun `older takes only what is older, across worlds, with every index row`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val all = PurgeCategory.entries.toSet()
            val report = purgeAndCheck(stack, PurgeSpec(all, PurgeFilter(before = 500)))

            assertTrue(report.matched > 0)
            assertEquals(
                listOf(503L),
                stack.worldLog.at(BlockPos(overworld, 3, 70, 3)).map { it.seq.raw },
                "newer stays"
            )
            assertTrue(stack.worldLog.at(BlockPos(overworld, 1, 70, 3)).isEmpty(), "older goes")
            assertEquals(1003L, stack.log.find(TxnId(1003))?.id?.raw, "a newer transaction stays")
            assertEquals(null, stack.log.find(TxnId(1001)), "an older one goes")
        }
    }

    @Test
    fun `a world takes only its own, and only the categories named`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val report = purgeAndCheck(stack, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(world = nether)))

            assertEquals(setOf(PurgeCategory.BLOCKS), report.tallies.keys)
            assertEquals(2L, report.matched, "the nether break and the nether boat")
            assertTrue(stack.worldLog.at(BlockPos(nether, 2, 70, 3)).isEmpty())
            assertEquals(1, stack.worldLog.at(BlockPos(overworld, 1, 70, 3)).size, "the overworld is left alone")
            assertEquals(1002L, stack.log.find(TxnId(1002))?.id?.raw, "items were not asked for")
        }
    }

    @Test
    fun `a player takes what they did, and the bookkeeping rows go without index rows`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val report = purgeAndCheck(
                stack,
                PurgeSpec(setOf(PurgeCategory.BLOCKS, PurgeCategory.ITEMS), PurgeFilter(player = steve.uuid)),
            )

            assertTrue(report.matched > 0)
            assertEquals(
                listOf(503L),
                stack.worldLog.at(BlockPos(overworld, 3, 70, 3)).map { it.seq.raw },
                "alex stays"
            )
            assertEquals(1003L, stack.log.find(TxnId(1003))?.id?.raw)
            assertEquals(null, stack.log.find(TxnId(1001)))
        }
    }

    @Test
    fun `containers by world keep the other worlds' layouts`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val report = purgeAndCheck(stack, PurgeSpec(setOf(PurgeCategory.CONTAINERS), PurgeFilter(world = nether)))

            assertEquals(1L, report.matched)
            val here = HolderId.Block(overworld, 1, 64, 1)
            assertTrue(stack.containerSlots.layoutAt(here, 9_999) != null, "the overworld chest keeps its layouts")
            assertEquals(null, stack.containerSlots.layoutAt(HolderId.Block(nether, 1, 64, 1), 9_999))
        }
    }

    @Test
    fun `a world or a player the store never saw matches nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            fill(stack)
            val spec = PurgeSpec(
                PurgeCategory.entries.toSet() - PurgeCategory.CONTAINERS,
                PurgeFilter(world = WorldId(UUID(9, 9)))
            )
            assertEquals(0L, purgeSome(stack.storage, spec).matched)
            val nobody = PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(player = UUID(9, 9)))
            assertEquals(0L, purgeSome(stack.storage, nobody).matched)
        }
    }

    @Test
    fun `a slice boundary does not skip or repeat a record`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            for (seq in 1L..25_000L) stack.worldLog.append(broke(seq, overworld, steve, seq))
            assertEquals(
                25_000L,
                previewPurge(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS))).matched,
                "a preview counts a row once, however many slices it takes"
            )

            val report = purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(before = 20_001)))

            assertEquals(20_000L, report.matched)
            assertEquals(25_000L, report.tallies.getValue(PurgeCategory.BLOCKS).total)
            assertEquals(5_000, rows(stack).getValue(Keys.WCHG))
            assertEquals(emptyList<String>(), orphans(stack))
        }
    }

    @Test
    fun `purging by player is refused for containers`() {
        val failure = runCatching {
            PurgeSpec(setOf(PurgeCategory.CONTAINERS), PurgeFilter(player = steve.uuid))
        }
        assertTrue(failure.isFailure, "a container layout is nobody's doing")
    }

    private suspend fun fillDays(stack: Stack, now: AtomicLong, days: Int, perDay: Int) {
        var seq = 1L
        for (d in 0 until days) {
            now.set(start + d * day + 1_000)
            stack.storage.batched {
                repeat(perDay) {
                    val world = if (seq % 2 == 0L) nether else overworld
                    stack.worldLog.append(broke(seq, world, steve, now.get() + seq % 500, x = (seq % 3000).toInt()))
                    seq++
                }
            }
            stack.storage.engine.flush()
        }
    }

    private fun clocked(dir: Path, now: AtomicLong) = Stack(dir, LsmConfig(clock = { now.get() }))

    @Test
    fun `an age purge throws whole days away as files, and the disk is back at once`(@TempDir dir: Path) = runTest {
        val now = AtomicLong(start)
        clocked(dir, now).use { stack ->
            fillDays(stack, now, days = 10, perDay = 5_000)
            val before = stack.storage.engine.stats().liveBytes
            assertEquals(10, stack.storage.engine.history(History.BLOCKS).size, "a window of history each")

            val report = purgeSome(
                stack.storage,
                PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(before = start + 5 * day)),
            )

            assertEquals(25_000L, report.matched)
            assertEquals(5, stack.storage.engine.history(History.BLOCKS).size, "five windows are gone")
            val after = stack.storage.engine.stats().liveBytes
            assertTrue(after < before * 0.7, "the disk says so, with no tombstones and no waiting: $before -> $after")
            assertEquals(report.bytes, before - after, "what the report says it freed is what the files weighed")
            assertEquals(25_000L, previewPurge(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS))).matched)
            assertEquals(emptyList<String>(), orphans(stack))
        }
    }

    @Test
    fun `a window that is only partly old is rewritten, and only that one`(@TempDir dir: Path) = runTest {
        val now = AtomicLong(start)
        clocked(dir, now).use { stack ->
            fillDays(stack, now, days = 4, perDay = 4_000)
            val untouched =
                stack.storage.engine.history(History.BLOCKS).filter { it.window > start / day + 2 }.map { it.id }

            val cut = start + 2 * day + 1_000 + 250
            val report = purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(before = cut)))

            assertTrue(
                report.matched > 8_000 && report.matched < 12_000,
                "two whole days and part of the third: ${report.matched}"
            )
            assertEquals(
                untouched,
                stack.storage.engine.history(History.BLOCKS).filter { it.window > start / day + 2 }.map { it.id },
                "the days after the cut are the same files",
            )
            assertEquals(emptyList<String>(), orphans(stack))
        }
    }

    @Test
    fun `a world takes its rows out of every day and gives their space back`(@TempDir dir: Path) = runTest {
        val now = AtomicLong(start)
        clocked(dir, now).use { stack ->
            fillDays(stack, now, days = 6, perDay = 6_000)
            val before = stack.storage.engine.stats().liveBytes

            val report = purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(world = nether)))

            assertEquals(18_000L, report.matched)
            val after = stack.storage.engine.stats().liveBytes
            assertTrue(after < before * 0.75, "about half of it is gone and the disk says so: $before -> $after")
            assertEquals(
                6,
                stack.storage.engine.history(History.BLOCKS).size,
                "the windows stay, with the other world in them"
            )
            assertEquals(18_000L, previewPurge(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS))).matched)
            assertEquals(emptyList<String>(), orphans(stack))
        }
    }

    @Test
    fun `history written before a restart is still in its windows`(@TempDir dir: Path) = runTest {
        val now = AtomicLong(start)
        clocked(dir, now).use { stack -> fillDays(stack, now, days = 3, perDay = 2_000) }

        clocked(dir, now).use { stack ->
            assertEquals(3, stack.storage.engine.history(History.BLOCKS).size)
            val report =
                purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(before = start + day)))
            assertEquals(2_000L, report.matched)
            assertEquals(2, stack.storage.engine.history(History.BLOCKS).size)
        }
    }

    @Test
    fun `whole windows only never touches the day on the edge`(@TempDir dir: Path) = runTest {
        val now = AtomicLong(start)
        clocked(dir, now).use { stack ->
            fillDays(stack, now, days = 4, perDay = 4_000)
            val edge = stack.storage.engine.history(History.BLOCKS).first { it.window == start / day + 2 }.id

            val spec = PurgeSpec(
                setOf(PurgeCategory.BLOCKS),
                PurgeFilter(before = start + 2 * day + 1_000 + 250),
                wholeWindowsOnly = true
            )
            val report = purgeSome(stack.storage, spec)

            assertEquals(8_000L, report.matched, "two whole days, and not a record of the third")
            assertEquals(2, stack.storage.engine.history(History.BLOCKS).size)
            assertTrue(
                stack.storage.engine.history(History.BLOCKS).any { it.id == edge },
                "the same file, not rewritten"
            )
        }
    }
}
