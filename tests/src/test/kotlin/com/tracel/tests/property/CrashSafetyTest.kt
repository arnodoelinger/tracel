package com.tracel.tests.property

import com.tracel.engine.journal.CrashPoint
import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.journal.SimulatedCrash
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CrashSafetyTest {
    private data class Scenario(val world: LedgerHarness, val chest: HolderId.Block, val rootLot: LotId)

    private suspend fun buildScenario(): Scenario {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())
        return Scenario(world, chest, root.id)
    }

    @Test
    fun `a crash before any step still resumes to a correct final state`() = runTest {
        val probe = buildScenario()
        val stepCount = RollbackPlanner(probe.world.repo, { true })
            .plan(listOf(probe.rootLot)).steps.size + 1 // +1 = final release

        for (crashAt in 0 until stepCount) {
            val (world, chest, rootLot) = buildScenario()
            val plan = RollbackPlanner(world.repo, { true }).plan(listOf(rootLot))
            val journal = InMemoryJournal()
            val job = RollbackJobId(1)
            val lease = world.acquireLease(job, plan)

            // First attempt must actually crash — otherwise the test proves nothing: if the crash injection silently
            // failed, the second attempt would just "do everything from scratch", and matching the expected final
            // state would prove nothing.
            val crash = runCatching {
                JournalExecutor(
                    RollbackExecutor(world.ledger, world.log, world::nextSeq),
                    journal,
                    world.leases,
                    world::nextTxn
                )
                    .execute(
                        lease,
                        plan,
                        target = RollbackTarget.Uniform(chest),
                        crashPoint = CrashPoint.before(crashAt)
                    )
            }.exceptionOrNull()
            assertTrue(crash is SimulatedCrash, "crash before step $crashAt should actually have fired, got $crash")

            // New exectutor, same journal, same job id, same plan: must resume from the crash point and reach
            // the correct final state.
            JournalExecutor(
                RollbackExecutor(world.ledger, world.log, world::nextSeq),
                journal,
                world.leases,
                world::nextTxn
            )
                .execute(lease, plan, target = RollbackTarget.Uniform(chest))

            assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw, "crash before step $crashAt")
            assertEquals(10L, world.ledger.census(diamond), "crash before step $crashAt: no duplication, no loss")
            assertNothingLeftInEscrow(world.ledger, job)
        }
    }

    private suspend fun assertNothingLeftInEscrow(ledger: LotLedger, job: RollbackJobId) {
        assertEquals(
            null,
            ledger.totalAt(HolderId.Escrow(job), diamond),
            "escrow must be fully drained once a job completes"
        )
    }
}
