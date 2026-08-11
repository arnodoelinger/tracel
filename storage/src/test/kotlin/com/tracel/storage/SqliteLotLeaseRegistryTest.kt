package com.tracel.storage

import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.ownership.SqliteLotLeaseRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * The point of a `SQLite`-backed registry over the in-memory one: a lease has to survive
 * whatever an in-memory map cannot — the process dying. Admin A's rollback job crashing
 * mid-apply must not silently free the lots it was holding for admin B to grab during
 * recovery.
 */
class SqliteLotLeaseRegistryTest {
    @Test
    fun `a lease survives closing and reopening the same file`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val job = RollbackJobId(1)

        TracelDatabase.open(path).use { db ->
            val granted = SqliteLotLeaseRegistry(db.exposed).acquire(job, setOf(LotId(1), LotId(2)))
            assertInstanceOf(LeaseAcquisition.Granted::class.java, granted)
        }

        // Restart: a brand-new registry instance, same file. A different job must still be
        // denied the lots job 1 took before the "crash" — the reservation is not process-local
        TracelDatabase.open(path).use { db ->
            val other = RollbackJobId(2)
            val denied = SqliteLotLeaseRegistry(db.exposed).acquire(other, setOf(LotId(1)))
            assertInstanceOf(LeaseAcquisition.Denied::class.java, denied)
            assertEquals(mapOf(LotId(1) to job), (denied as LeaseAcquisition.Denied).conflicts)

            // But job 1 itself can still reconfirm its own lease after the restart — this is
            // exactly what a resuming JournalExecutor needs to be able to do
            assertInstanceOf(
                LeaseAcquisition.Granted::class.java,
                SqliteLotLeaseRegistry(db.exposed).acquire(job, setOf(LotId(1), LotId(2))),
            )
        }
    }

    @Test
    fun `release persists - a freed lot stays free after reopening`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val job = RollbackJobId(1)

        TracelDatabase.open(path).use { db ->
            SqliteLotLeaseRegistry(db.exposed).acquire(job, setOf(LotId(1)))
            SqliteLotLeaseRegistry(db.exposed).release(job)
        }

        TracelDatabase.open(path).use { db ->
            val other = RollbackJobId(2)
            assertInstanceOf(LeaseAcquisition.Granted::class.java, SqliteLotLeaseRegistry(db.exposed).acquire(other, setOf(LotId(1))))
        }
    }

    @Test
    fun `transfer persists - the new holder survives reopening, the old one does not`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val from = RollbackJobId(1)
        val to = RollbackJobId(2)

        TracelDatabase.open(path).use { db ->
            SqliteLotLeaseRegistry(db.exposed).acquire(from, setOf(LotId(1)))
            val transferred = SqliteLotLeaseRegistry(db.exposed).transfer(from, to)
            assertEquals(setOf(LotId(1)), transferred)
        }

        TracelDatabase.open(path).use { db ->
            val registry = SqliteLotLeaseRegistry(db.exposed)

            // "to" survived the reopen holding what it was transferred; "from" holds nothing anymore
            assertInstanceOf(LeaseAcquisition.Granted::class.java, registry.acquire(to, setOf(LotId(1))))
            assertInstanceOf(LeaseAcquisition.Denied::class.java, registry.acquire(from, setOf(LotId(1))))
        }
    }

    @Test
    fun `reapAbandoned persists - a stale lease reaped in one session stays gone after reopening`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val stale = RollbackJobId(1)

        TracelDatabase.open(path).use { db ->
            SqliteLotLeaseRegistry(db.exposed).acquire(stale, setOf(LotId(1)))
            val now = System.currentTimeMillis()
            val reaped = SqliteLotLeaseRegistry(db.exposed).reapAbandoned(nowMillis = now + 10_000, maxAgeMillis = 5_000)
            assertEquals(setOf(stale), reaped)
        }

        TracelDatabase.open(path).use { db ->
            val other = RollbackJobId(2)
            assertInstanceOf(LeaseAcquisition.Granted::class.java, SqliteLotLeaseRegistry(db.exposed).acquire(other, setOf(LotId(1))))
        }
    }
}
