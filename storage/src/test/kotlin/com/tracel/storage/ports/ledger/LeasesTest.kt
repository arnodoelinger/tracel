package com.tracel.storage.ports.ledger

import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LeasesTest {
    @Test
    fun `a lease is all or nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val first = stack.leases.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2)))
            assertTrue(first is LeaseAcquisition.Granted)

            val second = stack.leases.acquire(RollbackJobId(2), setOf(LotId(2), LotId(3)))
            assertTrue(second is LeaseAcquisition.Denied)
            assertEquals(mapOf(LotId(2) to RollbackJobId(1)), (second as LeaseAcquisition.Denied).conflicts)

            assertTrue(stack.leases.acquire(RollbackJobId(3), setOf(LotId(3))) is LeaseAcquisition.Granted)
        }
    }

    @Test
    fun `a job may re-acquire its own lease, and that renews it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val job = RollbackJobId(1)
            assertTrue(stack.leases.acquire(job, setOf(LotId(1))) is LeaseAcquisition.Granted)
            assertTrue(stack.leases.acquire(job, setOf(LotId(1), LotId(2))) is LeaseAcquisition.Granted)
            assertEquals(emptySet<RollbackJobId>(), stack.leases.reapAbandoned(System.currentTimeMillis(), 60_000))
        }
    }

    @Test
    fun `leases survive a reopen, which is the entire reason they are on disk`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            assertTrue(stack.leases.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2))) is LeaseAcquisition.Granted)
        }
        Stack(dir).use { stack ->
            val stolen = stack.leases.acquire(RollbackJobId(2), setOf(LotId(1)))
            assertTrue(stolen is LeaseAcquisition.Denied, "a crashed job still holds its lots after a restart")
        }
    }

    @Test
    fun `transfer moves every lot atomically`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.leases.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2)))
            assertEquals(setOf(LotId(1), LotId(2)), stack.leases.transfer(RollbackJobId(1), RollbackJobId(2)))
            assertTrue(stack.leases.acquire(RollbackJobId(3), setOf(LotId(1))) is LeaseAcquisition.Denied)
            assertTrue(stack.leases.acquire(RollbackJobId(2), setOf(LotId(1), LotId(2))) is LeaseAcquisition.Granted)
        }
    }

    @Test
    fun `release frees everything a job held, and is idempotent`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.leases.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2)))
            stack.leases.release(RollbackJobId(1))
            stack.leases.release(RollbackJobId(1))
            assertTrue(stack.leases.acquire(RollbackJobId(2), setOf(LotId(1), LotId(2))) is LeaseAcquisition.Granted)
        }
    }

    @Test
    fun `reapAbandoned only takes leases nobody has renewed`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.leases.acquire(RollbackJobId(1), setOf(LotId(1)))
            assertEquals(emptySet<RollbackJobId>(), stack.leases.reapAbandoned(System.currentTimeMillis(), 60_000))
            assertEquals(
                setOf(RollbackJobId(1)),
                stack.leases.reapAbandoned(System.currentTimeMillis() + 120_000, 60_000),
            )
            assertTrue(stack.leases.acquire(RollbackJobId(2), setOf(LotId(1))) is LeaseAcquisition.Granted)
        }
    }
}
