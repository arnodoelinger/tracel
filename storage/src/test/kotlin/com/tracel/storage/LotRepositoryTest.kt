package com.tracel.storage

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.lot.LotEdge
import com.tracel.storage.ports.rebuildTotals
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.placedBlock
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors

class LotRepositoryTest {
    @Test
    fun `a created lot round-trips through the store exactly`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val lot = stack.repo.createLot(diamond, Quantity(5), TxnId(1))
            assertEquals(lot, stack.repo.lot(lot.id))
        }
    }

    @Test
    fun `placements preserve FIFO order by placement, not by lot creation`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val first = stack.repo.createLot(diamond, Quantity(1), TxnId(1))
            val second = stack.repo.createLot(diamond, Quantity(1), TxnId(1))

            stack.repo.place(chest, second.id, Quantity(1))
            stack.repo.place(chest, first.id, Quantity(1))

            assertEquals(listOf(second.id, first.id), stack.repo.accountQueue(chest, diamond).map { it.lot.id })
        }
    }

    @Test
    fun `replace keeps the same fifo position`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val original = stack.repo.createLot(diamond, Quantity(10), TxnId(1))
            stack.repo.place(chest, original.id, Quantity(10))
            val before = stack.repo.accountQueue(chest, diamond).single()

            val kept = stack.repo.createLot(diamond, Quantity(6), TxnId(1))
            stack.repo.replace(chest, original.id, kept.id, Quantity(6))

            val after = stack.repo.accountQueue(chest, diamond).single()
            assertEquals(before.fifoSeq, after.fifoSeq, "replace must not move the entry to the back of the queue")
            assertEquals(kept.id, after.lot.id)
            assertEquals(6L, after.remaining.raw)
            assertEquals(6L, stack.repo.totalOf(chest, diamond), "the running total must follow the replacement")
        }
    }

    @Test
    fun `remove retires a placement entirely`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val lot = stack.repo.createLot(diamond, Quantity(3), TxnId(1))
            stack.repo.place(chest, lot.id, Quantity(3))

            stack.repo.remove(chest, lot.id)

            assertEquals(emptyList<Any>(), stack.repo.accountQueue(chest, diamond))
            assertNull(stack.repo.currentHolderOf(lot.id))
            assertEquals(0L, stack.repo.totalOf(chest, diamond))
        }
    }

    @Test
    fun `the running total always equals what summing the queue would give`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            repeat(50) { stack.repo.place(chest, stack.repo.createLot(diamond, Quantity(2), TxnId(1)).id, Quantity(2)) }
            val doomed = stack.repo.accountQueue(chest, diamond).take(10).map { it.lot.id }
            doomed.forEach { stack.repo.remove(chest, it) }

            val summed = stack.repo.accountQueue(chest, diamond).sumOf { it.remaining.raw }
            assertEquals(summed, stack.repo.totalOf(chest, diamond))

            // And the cache really is only a cache
            rebuildTotals(stack.storage)
            assertEquals(summed, stack.repo.totalOf(chest, diamond), "a rebuild from the placements must agree")
        }
    }

    @Test
    fun `allPlacements filters by item key across holders`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            val diamondLot = stack.repo.createLot(diamond, Quantity(4), TxnId(1))
            val blockLot = stack.repo.createLot(diamondBlock, Quantity(1), TxnId(1))
            stack.repo.place(chest, diamondLot.id, Quantity(4))
            stack.repo.place(steve, blockLot.id, Quantity(1))

            assertEquals(listOf(diamondLot.id), stack.repo.allPlacements(diamond).map { it.lot.id })
        }
    }

    @Test
    fun `placementsAt returns every item key at a holder, not just one`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            val diamondLot = stack.repo.createLot(diamond, Quantity(4), TxnId(1))
            val blockLot = stack.repo.createLot(diamondBlock, Quantity(1), TxnId(1))
            val elsewhere = stack.repo.createLot(diamond, Quantity(7), TxnId(1))
            stack.repo.place(chest, diamondLot.id, Quantity(4))
            stack.repo.place(chest, blockLot.id, Quantity(1))
            stack.repo.place(steve, elsewhere.id, Quantity(7))

            assertEquals(setOf(diamondLot.id, blockLot.id), stack.repo.placementsAt(chest).map { it.lot.id }.toSet())
        }
    }

    @Test
    fun `every LotEdge variant round-trips its own fields exactly`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            val parent = LotId(1)

            val split = LotEdge.Split(LotId(2), parent, Quantity(2))
            val transform = LotEdge.Transform(LotId(3), parent, Quantity(3), TxnId(9), steve)
            val compensate = LotEdge.Compensate(LotId(4), parent, Quantity(1), RollbackJobId(7))

            stack.repo.recordEdge(split)
            stack.repo.recordEdge(transform)
            stack.repo.recordEdge(compensate)

            assertEquals(setOf(split, transform, compensate), stack.repo.edgesFrom(parent).toSet())
            assertEquals(setOf(transform), stack.repo.edgesInto(LotId(3)).toSet())
        }
    }

    @Test
    fun `a PlacedBlock holder round-trips through the codec, distinct from a Block holder`(@TempDir dir: Path) = runTest {
        val chest = placedBlock(0, 64, 0)
        val lotId = Stack(dir).use { stack ->
            val lot = stack.repo.createLot(diamond, Quantity(10), TxnId(1))
            stack.repo.place(chest, lot.id, Quantity(10))
            stack.repo.place(block(0, 64, 0), stack.repo.createLot(diamond, Quantity(3), TxnId(1)).id, Quantity(3))
            lot.id
        }

        Stack(dir).use { stack ->
            assertEquals(chest, stack.repo.currentHolderOf(lotId))
            assertEquals(10L, stack.repo.accountQueue(chest, diamond).single().remaining.raw)
            assertEquals(3L, stack.repo.totalOf(block(0, 64, 0), diamond), "a Block is not a PlacedBlock")
        }
    }

    @Test
    fun `writes from any thread still all land on the one storage thread`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val intruder = Executors.newSingleThreadExecutor()
            try {
                val lots = coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { stack.repo.createLot(diamond, Quantity(1), TxnId(1)) },
                        async(intruder.asCoroutineDispatcher()) { stack.repo.createLot(diamond, Quantity(2), TxnId(2)) },
                        async { withContext(Dispatchers.IO) { stack.repo.createLot(diamond, Quantity(3), TxnId(3)) } },
                    ).awaitAll()
                }
                assertEquals(3, lots.map { it.id }.distinct().size, "three callers, three distinct lots, no lost write")
            } finally {
                intruder.shutdown()
            }
        }
    }

    @Test
    fun `the same item key across many lots interns to exactly one id`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            repeat(20) { stack.repo.createLot(diamond, Quantity(1), TxnId(1)) }
            val chest = block(0, 64, 0)
            repeat(20) { stack.repo.place(chest, stack.repo.createLot(diamond, Quantity(1), TxnId(1)).id, Quantity(1)) }

            assertEquals(1, stack.storage.read { internedCount(this, com.tracel.storage.codec.Keys.NS_ITEM_KEY) })
            assertEquals(1, stack.storage.read { internedCount(this, com.tracel.storage.codec.Keys.NS_HOLDER) })
        }
    }

    private fun internedCount(unit: StorageUnit, namespace: Byte): Int {
        var count = 0
        unit.scan(byteArrayOf(com.tracel.storage.codec.Keys.INTERN_FORWARD, namespace)).use { cursor ->
            while (cursor.next()) count++
        }
        return count
    }
    @Test
    fun `relocating an account moves every lot, its quantities and its queue order`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val from = block(0, 64, 0)
            val to = block(0, 64, 1)
            val first = stack.ledger.mint(from, diamond, Quantity(3), stack.counters.nextTxnId())
            val second = stack.ledger.mint(from, diamond, Quantity(5), stack.counters.nextTxnId())

            stack.repo.relocate(from, to)

            assertNull(stack.ledger.totalAt(from, diamond), "nothing is left at the old address")
            assertEquals(8L, stack.ledger.totalAt(to, diamond)?.raw)
            assertEquals(
                listOf(first.id, second.id),
                stack.repo.accountQueue(to, diamond).map { it.lot.id },
                "a pushed chest keeps its FIFO order, or the next withdrawal takes the wrong lot",
            )
            assertEquals(to, stack.repo.currentHolderOf(first.id))
        }
    }

    @Test
    fun `relocating into an occupied account merges by queue position, not by arrival`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val from = block(0, 64, 0)
            val to = block(0, 64, 1)
            val older = stack.ledger.mint(from, diamond, Quantity(1), stack.counters.nextTxnId())
            val newer = stack.ledger.mint(to, diamond, Quantity(1), stack.counters.nextTxnId())

            stack.repo.relocate(from, to)

            assertEquals(
                listOf(older.id, newer.id),
                stack.repo.accountQueue(to, diamond).map { it.lot.id },
                "the relocated lot is older and must still be spent first",
            )
        }
    }

    @Test
    fun `relocating an empty account, or one onto itself, changes nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val here = block(0, 64, 0)
            stack.ledger.mint(here, diamond, Quantity(2), stack.counters.nextTxnId())

            stack.repo.relocate(block(9, 9, 9), here)
            stack.repo.relocate(here, here)

            assertEquals(2L, stack.ledger.totalAt(here, diamond)?.raw)
        }
    }


    @Test
    fun `batch reads agree with the one-at-a-time reads, dense and sparse`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val repo = stack.repo
            val chest = block(0, 64, 0)
            val parents = ArrayList<LotId>()
            repeat(200) { i ->
                val parent = repo.createLot(diamond, Quantity(4), TxnId(1))
                val child = repo.createLot(diamond, Quantity(2), TxnId(1))
                repo.recordEdge(LotEdge.Split(child.id, parent.id, Quantity(2)))
                repo.place(chest, parent.id, Quantity(4))
                parents += parent.id
            }

            for (ids in listOf(parents, parents.take(8))) {
                repo.forget()
                assertEquals(ids.associateWith { repo.edgesFrom(it) }, repo.edgesFromAll(ids))
                repo.forget()
                assertEquals(ids.associateWith { repo.lot(it) }, repo.lotsOfAll(ids))
                repo.forget()
                assertEquals(
                    ids.associateWith { repo.currentHolderOf(it)!! },
                    repo.currentHoldersOf(ids),
                )
                repo.forget()
                val children = ids.map { LotId(it.raw + 1) }
                assertEquals(children.associateWith { repo.edgesInto(it) }, repo.edgesIntoAll(children))
                repo.forget()
                val mixed = ids + children
                assertEquals(mixed.associateWith { repo.edgesFrom(it) }, repo.edgesFromAll(mixed))
            }
        }
    }

    @Test
    fun `prefetching lots warms the cache without inventing the ones that do not exist`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val repo = stack.repo
            val made = (1..200).map { repo.createLot(diamond, Quantity(it.toLong()), TxnId(1)) }
            repo.forget()

            val asked = made.map { it.id } + (900_000L..900_050L).map { LotId(it) }
            repo.prefetchLots(asked)

            for (lot in made) assertEquals(lot, repo.lot(lot.id))
            val absent = runCatching { repo.lot(LotId(900_000)) }.exceptionOrNull()
            assertTrue(absent is IllegalStateException, "a lot that does not exist must still say so, got $absent")
        }
    }
}
