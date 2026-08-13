package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.counters.SqliteCounters
import com.tracel.storage.log.SqliteTransactionLog
import com.tracel.storage.ownership.SqliteLotLeaseRegistry
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The bug this exists to prevent: a plugin restart that starts a fresh in-memory counter at 1
 * collides with [com.tracel.storage.schema.TransactionsTable] rows a previous session already
 * wrote, and every capture attempt fails with a duplicate-id error until the counter catches
 * back up on its own — silently, if whatever catches it treats that the same as any other
 * `IllegalStateException`.
 */
class SqliteCountersTest {
    @Test
    fun `a fresh counter on an empty database starts at 1`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val counters = SqliteCounters(db.exposed)
            assertEquals(TxnId(1), counters.nextTxnId())
            assertEquals(TxnId(2), counters.nextTxnId())
        }
    }

    @Test
    fun `the counter never repeats a value across reopens`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")

        val last = TracelDatabase.open(path).use { db ->
            val counters = SqliteCounters(db.exposed)
            repeat(5) { counters.nextTxnId() }
            counters.nextTxnId()
        }

        TracelDatabase.open(path).use { db ->
            val next = SqliteCounters(db.exposed).nextTxnId()
            assertTrue(next.raw > last.raw, "reopening must never hand out an id already used: got $next after $last")
        }
    }

    @Test
    fun `a counter added to a database that already has transaction rows bootstraps past them`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")

        // Simulate a database from before SqliteCounters existed: real transaction rows, no
        // id_counters row at all yet.
        TracelDatabase.open(path).use { db ->
            val log = SqliteTransactionLog(db.exposed)
            val chest = block(0, 64, 0)
            log.append(
                Transaction(
                    TxnId(42),
                    Seq(1),
                    epochMillis = 1L,
                    cause = CauseKind.UNKNOWN,
                    causedBy = null,
                    flows = listOf(Flow(diamond, Quantity(1), chest, chest, FlowKind.MOVE)),
                )
            )
        }

        TracelDatabase.open(path).use { db ->
            val counters = SqliteCounters(db.exposed)
            val next = counters.nextTxnId()
            assertTrue(next.raw > 42L, "must bootstrap past the highest id already in use, got $next")

            // And it must actually be usable — appending a transaction with the bootstrapped id
            // must not collide with the pre-existing row.
            val log = SqliteTransactionLog(db.exposed)
            log.append(Transaction(next, counters.nextSeq(), 2L, CauseKind.UNKNOWN, null, emptyList()))
        }
    }

    @Test
    fun `a fresh RollbackJobId counter on an empty database starts at 1`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val counters = SqliteCounters(db.exposed)
            assertEquals(RollbackJobId(1), counters.nextRollbackJobId())
            assertEquals(RollbackJobId(2), counters.nextRollbackJobId())
        }
    }

    @Test
    fun `the RollbackJobId counter never repeats a value across reopens`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")

        val last = TracelDatabase.open(path).use { db ->
            val counters = SqliteCounters(db.exposed)
            repeat(5) { counters.nextRollbackJobId() }
            counters.nextRollbackJobId()
        }

        TracelDatabase.open(path).use { db ->
            val next = SqliteCounters(db.exposed).nextRollbackJobId()
            assertTrue(next.raw > last.raw, "reopening must never hand out a job id already used: got $next after $last")
        }
    }

    @Test
    fun `a RollbackJobId counter added to a database with an existing lease bootstraps past it`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")

        // Simulate a database from before this counter existed: a real lease row, no id_counters row yet
        TracelDatabase.open(path).use { db ->
            SqliteLotLeaseRegistry(db.exposed).acquire(RollbackJobId(42), setOf(LotId(1)))
        }

        TracelDatabase.open(path).use { db ->
            val next = SqliteCounters(db.exposed).nextRollbackJobId()
            assertTrue(next.raw > 42L, "must bootstrap past the highest job id already in use, got $next")
        }
    }
}
