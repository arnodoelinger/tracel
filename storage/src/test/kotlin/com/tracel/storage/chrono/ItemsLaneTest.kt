package com.tracel.storage.chrono

import com.tracel.engine.container.ContainerSlotEntry
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.flow.FlowLot
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.log.Seq
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.Transaction
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.random.Random

class ItemsLaneTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))
    private val steve = player(1)
    private val alex = player(2)
    private val items = listOf("minecraft:diamond", "minecraft:cobblestone", "minecraft:oak_log").map { ItemKey(it) }
    private val millis0 = 1_700_000_000_000L

    private fun transactions(n: Int): List<Transaction> {
        val random = Random(3)
        var millis = millis0
        val chests = List(6) { HolderId.Block(if (it == 5) nether else overworld, it * 40, 64, it * 25 - 60) }
        return (1..n).map { i ->
            millis += random.nextLong(0, 3000)
            val from = if (random.nextBoolean()) steve else chests[random.nextInt(chests.size)]
            val to = if (random.nextBoolean()) alex else chests[random.nextInt(chests.size)]
            val flows = (0 until random.nextInt(1, 4)).map {
                Flow(
                    items[random.nextInt(items.size)],
                    Quantity(random.nextLong(1, 64)),
                    from,
                    to,
                    if (random.nextInt(5) == 0) FlowKind.MINT else FlowKind.MOVE
                )
            }
            val cause = if (random.nextInt(9) == 0) CauseKind.ROLLBACK else CauseKind.PLAYER_ACTION
            Transaction(
                TxnId(i.toLong()), Seq(i.toLong()), millis, cause, if (random.nextInt(6) == 0) null else from, flows,
                flows.indices.filter { random.nextBoolean() }
                    .map { FlowLot(it, LotId(random.nextLong(1, 500)), Quantity(random.nextLong(1, 64))) },
                at = if (random.nextInt(4) == 0) null else BlockPos(
                    overworld,
                    random.nextInt(-60, 60),
                    64,
                    random.nextInt(-60, 60)
                ),
            )
        }
    }

    private fun touches(t: Transaction): List<HolderId?> =
        listOf(t.causedBy) + t.flows.flatMap { listOf(it.source, it.destination) }

    private fun blocks(t: Transaction): List<BlockPos> =
        (listOfNotNull(t.at) + touches(t).filterIsInstance<HolderId.Block>()
            .map { BlockPos(it.world, it.x, it.y, it.z) })

    private fun admits(f: LookupFilter, t: Transaction): Boolean {
        if (t.cause.isBookkeeping || !f.admitsTime(t.epochMillis)) return false
        if (f.causes.isNotEmpty() && t.cause !in f.causes) return false
        if (f.holders.isNotEmpty() && touches(t).none { it in f.holders }) return false
        if (f.excludedHolders.isNotEmpty() && touches(t).any { it in f.excludedHolders }) return false
        if (f.itemKeys.isNotEmpty() && t.flows.none { it.itemKey in f.itemKeys }) return false
        if (f.world != null && blocks(t).none { it.world == f.world }) return false
        val region = f.region
        return region == null || blocks(t).any { region.contains(it) }
    }

    private suspend fun assertItems(stack: Stack, written: List<Transaction>, label: String) {
        val since = millis0 + (written.maxOf { it.epochMillis } - millis0) / 2
        val all = Int.MAX_VALUE
        val filters = listOf(
            LookupFilter(limit = all),
            LookupFilter(limit = 40, offset = 9),
            LookupFilter(since = since, limit = all),
            LookupFilter(holders = setOf(steve), limit = all),
            LookupFilter(holders = setOf(alex), since = since, limit = all),
            LookupFilter(excludedHolders = setOf(steve), limit = all),
            LookupFilter(itemKeys = setOf(items[0]), limit = all),
            LookupFilter(causes = setOf(CauseKind.PLAYER_ACTION), limit = all),
            LookupFilter(world = nether, limit = all),
            LookupFilter(holders = setOf(HolderId.Block(overworld, 40, 64, -35)), limit = all),
            LookupFilter(holders = setOf(HolderId.Block(overworld, 80, 64, -10), steve), since = since, limit = all),
            LookupFilter(region = LookupRegion(overworld, -1, 2, -4, 1), limit = all),
            LookupFilter(
                region = LookupRegion(overworld, -4, 4, -4, 4),
                holders = setOf(steve),
                itemKeys = setOf(items[1]),
                limit = all
            ),
        )
        for ((i, f) in filters.withIndex()) {
            val want = written.filter { admits(f, it) }.sortedByDescending { it.seq.raw }.drop(f.offset).take(f.limit)
                .map { it.copy(lots = emptyList()) }
            val got = stack.log.query(f).map { it.copy(lots = emptyList()) }
            assertEquals(want, got, "$label: query $i, $f")
        }
        for (t in written.shuffled(Random(2)).take(60)) {
            assertEquals(t, stack.log.find(t.id), "$label: find ${t.id}")
            assertEquals(
                t.lots.sortedBy { it.flowIndex },
                stack.log.lotsAt(t.seq).sortedBy { it.flowIndex },
                "$label: lots of ${t.seq}"
            )
        }
        assertNull(stack.log.find(TxnId(999_999)))
        val together = stack.worldLog.queryTogether(
            stack.log,
            LookupFilter(since = since, limit = all),
            includeWorld = false
        ).second
        assertEquals(
            written.filter { admits(LookupFilter(since = since), it) }.sortedByDescending { it.seq.raw },
            together,
            "$label: with lots"
        )
    }

    @Test
    fun `items answer every filter the way a list would, merged or not, and after a reopen`(@TempDir dir: Path) =
        runTest {
            val written = transactions(900)
            Stack(dir).use { stack ->
                for (t in written.take(500)) stack.log.append(t)
                assertItems(stack, written.take(500), "fragments")
                Chrono.of(stack.storage).mergeAll()
                for (t in written.drop(500)) stack.log.append(t)
                assertItems(stack, written, "merged then more")
            }
            Stack(dir).use { assertItems(it, written, "reopened") }
        }

    @Test
    fun `a container's layout is the newest recording at or before the moment`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = HolderId.Block(overworld, 3, 64, 3)
            val barrel = HolderId.Block(nether, 1, 64, 1)
            stack.containerSlots.record(chest, 100, listOf(ContainerSlotEntry(0, items[0], 5)))
            stack.containerSlots.record(
                chest,
                200,
                listOf(ContainerSlotEntry(0, items[0], 5), ContainerSlotEntry(4, items[2], 64))
            )
            stack.containerSlots.record(barrel, 150, listOf(ContainerSlotEntry(2, items[1], 9)))
            stack.containerSlots.record(chest, 300, emptyList())
            Chrono.of(stack.storage).mergeAll()
            assertNull(stack.containerSlots.layoutAt(chest, 50))
            assertEquals(listOf(ContainerSlotEntry(0, items[0], 5)), stack.containerSlots.layoutAt(chest, 150))
            assertEquals(2, stack.containerSlots.layoutAt(chest, 250)?.size)
            assertEquals(emptyList<ContainerSlotEntry>(), stack.containerSlots.layoutAt(chest, 999))
            assertEquals(listOf(ContainerSlotEntry(2, items[1], 9)), stack.containerSlots.layoutAt(barrel, 999))
            assertNull(stack.containerSlots.layoutAt(chest, 999, limit = 0))
        }
    }
}
