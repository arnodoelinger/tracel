package com.tracel.storage

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The same properties `com.tracel.tests.property.RestoreTest` and
 * `com.tracel.tests.property.DeterminismTest` prove against
 * [com.tracel.engine.ledger.InMemoryLotRepository], reproduced against the real `SQLite`
 * backend. Nothing here is a new property — it's the same guarantee, proven on the backend
 * that will actually ship.
 */
class PropertyEquivalenceTest {
    @Test
    fun `restore - census after rollback matches the census at the traced checkpoint`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val ledger = LotLedger(repo)
            val chest = block(0, 64, 0)
            val p1 = player(1)
            val p2 = player(2)
            val p3 = player(3)
            var nextTxnRaw = 1L
            fun nextTxn() = TxnId(nextTxnRaw++)

            val root = ledger.mint(chest, diamond, Quantity(20), nextTxn())
            val checkpointCensus = ledger.census(diamond)

            ledger.move(chest, p1, diamond, Quantity(12), nextTxn())
            ledger.move(p1, p2, diamond, Quantity(5), nextTxn())
            ledger.move(chest, p3, diamond, Quantity(8), nextTxn())
            ledger.move(p2, p3, diamond, Quantity(2), nextTxn())
            ledger.move(p3, p1, diamond, Quantity(1), nextTxn())

            val plan = RollbackPlanner(repo, WorldQuery { true }).plan(listOf(root.id))
            JournalExecutor(RollbackExecutor(ledger), InMemoryJournal())
                .execute(RollbackJobId(1), plan, restoreTo = chest, txn = nextTxn())

            assertEquals(checkpointCensus, ledger.census(diamond))
            assertEquals(20L, ledger.totalAt(chest, diamond)?.raw)
        }
    }

    @Test
    fun `determinism - identical operation sequences produce identical rollback plans on fresh databases`(@TempDir dir: Path) {
        fun runScenario(path: Path): RollbackPlan {
            TracelDatabase.open(path).use { db ->
                val repo = SqliteLotRepository(db.exposed)
                val ledger = LotLedger(repo)
                val chest = block(0, 64, 0)
                val p1 = player(1)
                val p2 = player(2)
                var nextTxnRaw = 1L
                fun nextTxn() = TxnId(nextTxnRaw++)

                val root = ledger.mint(chest, diamond, Quantity(10), nextTxn())
                ledger.move(chest, p1, diamond, Quantity(6), nextTxn())
                ledger.move(chest, p2, diamond, Quantity(4), nextTxn())
                ledger.move(p1, p2, diamond, Quantity(2), nextTxn())
                return RollbackPlanner(repo, WorldQuery { true }).plan(listOf(root.id))
            }
        }

        val first = runScenario(dir.resolve("first.sqlite"))
        val second = runScenario(dir.resolve("second.sqlite"))
        assertEquals(first, second, "two fresh databases running the identical sequence must assign identical ids")
    }
}
