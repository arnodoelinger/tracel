package com.tracel.storage

import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.model.lot.LotEdge
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.placedBlock
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SqliteLotRepositoryTest {
    @Test
    fun `a created lot round-trips through the database exactly`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val lot = repo.createLot(diamond, Quantity(5), TxnId(1))
            assertEquals(lot, repo.lot(lot.id))
        }
    }

    @Test
    fun `placements preserve FIFO order by placement, not by lot creation`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val first = repo.createLot(diamond, Quantity(1), TxnId(1))
            val second = repo.createLot(diamond, Quantity(1), TxnId(1))

            // Placed in reverse creation order: FIFO must follow placement, not creation.
            repo.place(chest, second.id, Quantity(1))
            repo.place(chest, first.id, Quantity(1))

            val queue = repo.accountQueue(chest, diamond)
            assertEquals(listOf(second.id, first.id), queue.map { it.lot.id })
        }
    }

    @Test
    fun `replace keeps the same fifo position`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val original = repo.createLot(diamond, Quantity(10), TxnId(1))
            repo.place(chest, original.id, Quantity(10))
            val before = repo.accountQueue(chest, diamond).single()

            val kept = repo.createLot(diamond, Quantity(6), TxnId(1))
            repo.replace(chest, original.id, kept.id, Quantity(6))

            val after = repo.accountQueue(chest, diamond).single()
            assertEquals(before.fifoSeq, after.fifoSeq, "replace must not move the entry to the back of the queue")
            assertEquals(kept.id, after.lot.id)
            assertEquals(6L, after.remaining.raw)
        }
    }

    @Test
    fun `remove retires a placement entirely`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val lot = repo.createLot(diamond, Quantity(3), TxnId(1))
            repo.place(chest, lot.id, Quantity(3))

            repo.remove(chest, lot.id)

            assertEquals(emptyList<Any>(), repo.accountQueue(chest, diamond))
            assertNull(repo.currentHolderOf(lot.id))
        }
    }

    @Test
    fun `allPlacements filters by item key across holders`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val steve = player(1)
            val diamondLot = repo.createLot(diamond, Quantity(4), TxnId(1))
            val blockLot = repo.createLot(diamondBlock, Quantity(1), TxnId(1))
            repo.place(chest, diamondLot.id, Quantity(4))
            repo.place(steve, blockLot.id, Quantity(1))

            assertEquals(listOf(diamondLot.id), repo.allPlacements(diamond).map { it.lot.id })
        }
    }

    @Test
    fun `placementsAt returns every item key at a holder, not just one`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chest = block(0, 64, 0)
            val steve = player(1)
            val diamondLot = repo.createLot(diamond, Quantity(4), TxnId(1))
            val blockLot = repo.createLot(diamondBlock, Quantity(1), TxnId(1))
            val elsewhere = repo.createLot(diamond, Quantity(7), TxnId(1))
            repo.place(chest, diamondLot.id, Quantity(4))
            repo.place(chest, blockLot.id, Quantity(1))
            repo.place(steve, elsewhere.id, Quantity(7))

            assertEquals(
                setOf(diamondLot.id, blockLot.id),
                repo.placementsAt(chest).map { it.lot.id }.toSet(),
            )
        }
    }

    @Test
    fun `every LotEdge variant round-trips its own fields exactly`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val steve = player(1)
            val parent = LotId(1)

            val split = LotEdge.Split(LotId(2), parent, Quantity(2))
            val transform = LotEdge.Transform(LotId(3), parent, Quantity(3), TxnId(9), steve)
            val compensate = LotEdge.Compensate(LotId(4), parent, Quantity(1), RollbackJobId(7))

            repo.recordEdge(split)
            repo.recordEdge(transform)
            repo.recordEdge(compensate)

            assertEquals(setOf(split, transform, compensate), repo.edgesFrom(parent).toSet())
        }
    }

    @Test
    fun `a PlacedBlock holder round-trips through the codec, distinct from a Block holder`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val placedBlockHolder = placedBlock(0, 64, 0)
        val chest = block(0, 64, 0)
        val placedLotId: LotId

        TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val chestLot = repo.createLot(diamond, Quantity(1), TxnId(1))
            repo.place(chest, chestLot.id, Quantity(1))
            val placedLot = repo.createLot(diamondBlock, Quantity(1), TxnId(1))
            repo.place(placedBlockHolder, placedLot.id, Quantity(1))
            placedLotId = placedLot.id
        }

        TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            assertEquals(placedBlockHolder, repo.currentHolderOf(placedLotId))
            assertEquals(1L, repo.accountQueue(placedBlockHolder, diamondBlock).single().remaining.raw)
            assertEquals(emptyList<Any>(), repo.accountQueue(chest, diamondBlock))
        }
    }

    @Test
    fun `state survives closing and reopening the same file`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val chest = block(0, 64, 0)
        val lotId: LotId

        TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val lot = repo.createLot(diamond, Quantity(10), TxnId(1))
            repo.place(chest, lot.id, Quantity(10))
            lotId = lot.id
        }

        TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            assertEquals(chest, repo.currentHolderOf(lotId))
            assertEquals(10L, repo.accountQueue(chest, diamond).single().remaining.raw)
        }
    }

    @Test
    fun `a second thread writing to the same repository fails loudly`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            repo.createLot(diamond, Quantity(1), TxnId(1))

            val intruder = Executors.newSingleThreadExecutor()
            try {
                val future = intruder.submit { repo.createLot(diamond, Quantity(1), TxnId(2)) }
                val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
                assertInstanceOf(IllegalStateException::class.java, failure.cause)
            } finally {
                intruder.shutdown()
            }
        }
    }
}
