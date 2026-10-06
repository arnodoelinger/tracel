package com.tracel.tests.property

import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.involution.apply.InvolutionExecutor
import com.tracel.engine.rollback.involution.plan.InvolutionPlanner
import com.tracel.engine.rollback.job.record.memory.InMemoryRollbackJobRepository
import com.tracel.engine.rollback.job.record.RollbackJobRecord
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UnattributedHarvestUndoCycleTest {
    private val recoveryPoint = block(0, 64, 0)
    private val steve = player(1)
    private val sweetBerries = ItemKey("minecraft:sweet_berries")

    @Test
    fun `a dozen rollback-undo cycles of an unattributed mint leave the census exactly where they found it`() =
        runTest {
            val world = LedgerHarness()
            val jobs = InMemoryRollbackJobRepository()

            val root = world.ledger.mint(steve, sweetBerries, Quantity(1), world.nextTxn())

            assertEquals(1L, world.ledger.totalAt(steve, sweetBerries)?.raw)
            assertEquals(1L, world.ledger.census(sweetBerries))

            repeat(12) { cycle ->
                val job = RollbackJobId(cycle + 1L)

                val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
                val target = RollbackTarget.Uniform(recoveryPoint)
                jobs.save(RollbackJobRecord(job, plan, target))
                JournalExecutor(
                    RollbackExecutor(world.ledger, world.log, world::nextSeq),
                    InMemoryJournal(),
                    world.leases,
                    world::nextTxn
                )
                    .execute(world.acquireLease(job, plan), plan, target)

                assertEquals(
                    1L,
                    world.ledger.totalAt(recoveryPoint, sweetBerries)?.raw,
                    "cycle $cycle: the rollback put it back at the recovery point"
                )
                assertEquals(
                    1L,
                    world.ledger.census(sweetBerries),
                    "cycle $cycle: a rollback of an unattributed mint still creates nothing"
                )

                val undoLease = world.acquireLease(job, plan)
                val executor = InvolutionExecutor(world.ledger, world.log, world::nextSeq)
                for (step in InvolutionPlanner(world.repo).plan(jobs.find(job)!!)) {
                    executor.apply(undoLease, step, world.nextTxn())
                }
                world.leases.release(job)

                assertEquals(
                    1L,
                    world.ledger.totalAt(steve, sweetBerries)?.raw,
                    "cycle $cycle: undo gave it back to the player"
                )
                assertEquals(
                    1L,
                    world.ledger.census(sweetBerries),
                    "cycle $cycle: and an undo of it creates nothing either"
                )
            }
        }
}
