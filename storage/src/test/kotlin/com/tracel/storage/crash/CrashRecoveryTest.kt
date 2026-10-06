package com.tracel.storage.crash

import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.journal.crash.CrashPoint
import com.tracel.engine.rollback.journal.crash.SimulatedCrash
import com.tracel.engine.rollback.lease.acquisition.LeaseAcquisition
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CrashRecoveryTest {
    @Test
    fun `a rollback crashed mid-flight resumes correctly after a real reopen`(@TempDir dir: Path) = runTest {
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = Stack(dir).use { stack ->
            val lot = stack.mint(chest, diamond, 10)
            stack.move(chest, steve, diamond, 10)
            stack.burn(steve, diamond, 4, SinkKind.HAZARD)
            lot.id
        }

        val job = RollbackJobId(1)

        val crash = Stack(dir).use { stack ->
            val plan = RollbackPlanner(stack.repo, { true }).plan(listOf(root))
            val lease = (stack.leases.acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease
            runCatching {
                JournalExecutor(
                    RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
                    stack.journal,
                    stack.leases,
                    stack.counters::nextTxnId,
                ).execute(lease, plan, target = RollbackTarget.Uniform(chest), crashPoint = CrashPoint.before(1))
            }.exceptionOrNull()
        }
        assertTrue(crash is SimulatedCrash, "the crash injection must actually have fired, got $crash")

        Stack(dir).use { stack ->
            val plan = RollbackPlanner(stack.repo, { true }).plan(listOf(root))
            val lease = (stack.leases.acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease

            JournalExecutor(
                RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
                stack.journal,
                stack.leases,
                stack.counters::nextTxnId,
            ).execute(lease, plan, target = RollbackTarget.Uniform(chest))

            assertEquals(10L, stack.ledger.totalAt(chest, diamond)?.raw)
            assertEquals(10L, stack.ledger.census(diamond), "no duplication, no loss, across a real restart")
            assertNull(stack.ledger.totalAt(HolderId.Escrow(job), diamond), "escrow must drain once the job completes")
        }
    }

    @Test
    fun `re-running a completed rollback changes nothing`(@TempDir dir: Path) = runTest {
        val chest = block(0, 64, 0)
        val steve = player(1)

        Stack(dir).use { stack ->
            val root = stack.mint(chest, diamond, 10)
            stack.move(chest, steve, diamond, 6)

            val plan = RollbackPlanner(stack.repo, { true }).plan(listOf(root.id))
            val executor = JournalExecutor(
                RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
                stack.journal,
                stack.leases,
                stack.counters::nextTxnId,
            )

            val job = RollbackJobId(1)
            executor.execute(
                (stack.leases.acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease,
                plan,
                target = RollbackTarget.Uniform(chest),
            )
            val afterFirst = stack.ledger.totalAt(chest, diamond)?.raw
            val censusAfterFirst = stack.ledger.census(diamond)

            executor.execute(
                (stack.leases.acquire(job, plan.touchedLots) as LeaseAcquisition.Granted).lease,
                plan,
                target = RollbackTarget.Uniform(chest),
            )

            assertEquals(
                afterFirst,
                stack.ledger.totalAt(chest, diamond)?.raw,
                "a rollback is idempotent or it is a dupe machine"
            )
            assertEquals(censusAfterFirst, stack.ledger.census(diamond))
        }
    }
}
