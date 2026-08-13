package com.tracel.storage

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.journal.SimulatedCrash
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.log.InMemoryTransactionLog
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.storage.journal.SqliteJournal
import com.tracel.storage.ledger.SqliteLotRepository
import com.tracel.storage.ownership.SqliteLotLeaseRegistry
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The claim [SqliteJournal] and [SqliteLotRepository] exist to make true: a rollback that
 * crashes mid-flight, on a process that then genuinely restarts — new objects, the same file
 * on disk, nothing carried over in memory — still resumes to the correct final state.
 *
 * `com.tracel.tests.property.CrashSafetyTest` proves the same claim for the in-memory
 * repositories, where "crash" can only ever be simulated within one still-running process.
 * This is the one that actually closes the file and reopens it.
 */
class CrashRecoveryTest {
    @Test
    fun `a rollback crashed mid-flight resumes correctly after a real file reopen`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("db.sqlite")
        val chest = block(0, 64, 0)
        val steve = player(1)
        var nextTxnRaw = 1L
        fun nextTxn() = TxnId(nextTxnRaw++)
        var nextSeqRaw = 1L
        fun nextSeq() = Seq(nextSeqRaw++)
        val log = InMemoryTransactionLog()

        val root = TracelDatabase.open(path).use { db ->
            val ledger = LotLedger(SqliteLotRepository(db.exposed))
            val root = ledger.mint(chest, diamond, Quantity(10), nextTxn())
            ledger.move(chest, steve, diamond, Quantity(10), nextTxn())
            ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, nextTxn())
            root.id
        }

        val job = RollbackJobId(1)

        // A fresh connection to the same file, deliberately killed right before step 1 —
        // the same failure mode a real server crash mid-rollback would produce.
        val crash = TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val plan = RollbackPlanner(repo, { true }).plan(listOf(root))
            val lease = (SqliteLotLeaseRegistry(db.exposed).acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease
            runCatching {
                JournalExecutor(RollbackExecutor(LotLedger(repo), log, ::nextSeq), SqliteJournal(db.exposed), SqliteLotLeaseRegistry(db.exposed), ::nextTxn)
                    .execute(lease, plan, restoreTo = chest, crashPoint = CrashPoint.before(1))
            }.exceptionOrNull()
        }
        assertTrue(crash is SimulatedCrash, "the crash injection must actually have fired, got $crash")

        // Restart: brand-new TracelDatabase, brand-new repository / journal / executor / lease-registry
        // objects, same file — the lease taken before the crash must still be sitting in the database,
        // exactly like the journal's step-completion rows are.
        TracelDatabase.open(path).use { db ->
            val repo = SqliteLotRepository(db.exposed)
            val ledger = LotLedger(repo)
            val plan = RollbackPlanner(repo, { true }).plan(listOf(root))
            val lease = (SqliteLotLeaseRegistry(db.exposed).acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease

            JournalExecutor(RollbackExecutor(ledger, log, ::nextSeq), SqliteJournal(db.exposed), SqliteLotLeaseRegistry(db.exposed), ::nextTxn)
                .execute(lease, plan, restoreTo = chest)

            assertEquals(10L, ledger.totalAt(chest, diamond)?.raw)
            assertEquals(10L, ledger.census(diamond), "no duplication, no loss, across a real restart")
            assertNull(ledger.totalAt(HolderId.Escrow(job), diamond), "escrow must be fully drained once the job completes")
        }
    }
}
