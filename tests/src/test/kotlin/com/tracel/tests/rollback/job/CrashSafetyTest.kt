package com.tracel.tests.rollback.job

import com.tracel.engine.rollback.journal.crash.CrashPoint
import com.tracel.engine.rollback.journal.crash.SimulatedCrash
import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.lot.LotId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CrashSafetyTest {
    private data class Scenario(val world: LedgerHarness, val chest: HolderId.Block, val root: LotId)

    private suspend fun scenario(): Scenario {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        world.burn(steve, diamond, 4)
        return Scenario(world, chest, root.id)
    }

    @Test
    fun `a crash before any step still resumes to a correct final state`() = runTest {
        val probe = scenario()
        val points = probe.world.planner().plan(listOf(probe.root)).steps.size + 1 // the final release is a step too

        for (crashAt in 0 until points) {
            val (world, chest, root) = scenario()
            val plan = world.planner().plan(listOf(root))
            val journal = InMemoryJournal()
            val job = world.nextJob()
            val lease = world.acquireLease(job, plan)
            val target = RollbackTarget.Uniform(chest)

            val crash = runCatching {
                world.journalExecutor(journal).execute(lease, plan, target, CrashPoint.before(crashAt))
            }.exceptionOrNull()
            assertTrue(crash is SimulatedCrash, "crash before step $crashAt should have fired, got $crash")

            world.journalExecutor(journal).execute(lease, plan, target)

            assertEquals(10L, world.count(chest, diamond), "crash before step $crashAt")
            assertEquals(10L, world.census(diamond), "crash before step $crashAt: no duplication, no loss")
            assertNull(
                world.ledger.totalAt(HolderId.Escrow(job), diamond),
                "crash before step $crashAt: escrow drained"
            )
        }
    }
}
