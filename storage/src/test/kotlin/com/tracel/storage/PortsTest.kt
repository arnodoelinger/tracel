package com.tracel.storage

import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.LotContribution
import com.tracel.engine.rollback.RollbackJobRecord
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.id.TxnId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

/** The smaller ports: progress, ownership, plans, deliveries, counters. */
class PortsTest {
    @Test
    fun `journal progress survives a reopen and does not cross between rollback and undo`(@TempDir dir: Path) = runTest {
        val job = RollbackJobId(4)
        Stack(dir).use { stack ->
            stack.journal.markCompleted(job, 3)
            assertTrue(stack.journal.isCompleted(job, 3))
            assertFalse(stack.involutionJournal.isCompleted(job, 3), "undo progress is not rollback progress")
        }
        Stack(dir).use { stack ->
            assertTrue(stack.journal.isCompleted(job, 3), "a journal that forgets across a restart is not a journal")
            assertFalse(stack.journal.isCompleted(job, 4))
        }
    }

    @Test
    fun `marking the same step twice is a no-op`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.journal.markCompleted(RollbackJobId(1), 0)
            stack.journal.markCompleted(RollbackJobId(1), 0)
            assertTrue(stack.journal.isCompleted(RollbackJobId(1), 0))
        }
    }

    @Test
    fun `a lease is all or nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val first = stack.leases.acquire(RollbackJobId(1), setOf(LotId(1), LotId(2)))
            assertTrue(first is LeaseAcquisition.Granted)

            val second = stack.leases.acquire(RollbackJobId(2), setOf(LotId(2), LotId(3)))
            assertTrue(second is LeaseAcquisition.Denied)
            assertEquals(mapOf(LotId(2) to RollbackJobId(1)), (second as LeaseAcquisition.Denied).conflicts)

            // Nothing was half-taken: lot 3 is still free
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

    @Test
    fun `every rollback step variant round-trips`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = player(1)
            val chest = block(0, 64, 0)
            val record = RollbackJobRecord(
                RollbackJobId(3),
                RollbackPlan(
                    listOf(
                        RollbackStep.Unmake(
                            LotId(10),
                            listOf(LotContribution(LotId(11), Quantity(4)), LotContribution(LotId(12), Quantity(5))),
                            TxnId(99),
                            steve,
                        ),
                        RollbackStep.Take(LotId(11), Quantity(4), steve),
                        RollbackStep.Mint(LotId(13), Quantity(2), SinkKind.LAVA),
                        RollbackStep.Debt(LotId(14), Quantity(1), UUID(7, 8)),
                    ),
                ),
                restoreTo = chest,
            )
            stack.jobs.save(record)
            assertEquals(record, stack.jobs.find(RollbackJobId(3)))
            assertNull(stack.jobs.find(RollbackJobId(4)))
        }
    }

    @Test
    fun `a plan with more than ten steps still comes back in order`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steps = (1..40).map { RollbackStep.Take(LotId(it.toLong()), Quantity(1), player(1)) }
            stack.jobs.save(RollbackJobRecord(RollbackJobId(1), RollbackPlan(steps), block(0, 64, 0)))
            val found = stack.jobs.find(RollbackJobId(1))?.plan?.steps.orEmpty()
            assertEquals(steps, found, "step 10 must not sort before step 9")
        }
    }

    @Test
    fun `pending deliveries are claimed exactly once`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = UUID(0, 1)
            stack.pending.enqueueAll(steve, mapOf(diamond to 5L, diamondBlock to -1L), RollbackJobId(1), 1000)

            val claimed = stack.pending.claimFor(steve)
            assertEquals(mapOf(diamond to 5L, diamondBlock to -1L), claimed.associate { it.itemKey to it.delta })
            assertEquals(emptyList<Any>(), stack.pending.claimFor(steve), "a claim empties the queue")
        }
    }

    @Test
    fun `one player's deliveries are not another's`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.pending.enqueueAll(UUID(0, 1), mapOf(diamond to 5L), RollbackJobId(1), 1000)
            stack.pending.enqueueAll(UUID(0, 2), mapOf(diamond to 9L), RollbackJobId(1), 1000)
            assertEquals(listOf(5L), stack.pending.claimFor(UUID(0, 1)).map { it.delta })
            assertEquals(listOf(9L), stack.pending.claimFor(UUID(0, 2)).map { it.delta })
        }
    }

    @Test
    fun `counters never hand out an id twice, across a restart`(@TempDir dir: Path) = runTest {
        val first = Stack(dir).use { stack -> (1..10).map { stack.counters.nextTxnId().raw } }
        val second = Stack(dir).use { stack -> (1..10).map { stack.counters.nextTxnId().raw } }
        assertEquals(20, (first + second).distinct().size, "a reserved block is forfeited on restart, never reused")
        assertTrue(second.min() > first.max(), "ids only ever go forwards")
    }

    @Test
    fun `different counters do not share a sequence`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            assertEquals(1L, stack.counters.nextTxnId().raw)
            assertEquals(1L, stack.counters.nextSeq().raw)
            assertEquals(1L, stack.counters.nextLotId().raw)
        }
    }
}
