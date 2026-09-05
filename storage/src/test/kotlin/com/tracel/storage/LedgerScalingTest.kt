package com.tracel.storage

import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.TimeSource

class LedgerScalingTest {
    private suspend fun perStepMicros(dir: Path, steps: Int): Double {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val steve = player(1)
            val roots = (1..steps).map { stack.ledger.mint(chest, diamond, Quantity(1), stack.counters.nextTxnId()).id }
            stack.ledger.move(chest, steve, diamond, Quantity(steps.toLong()), stack.counters.nextTxnId())

            val coordinator = RollbackJobCoordinator(
                stack.repo,
                { true },
                stack.leases,
                JournalExecutor(
                    RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
                    stack.journal,
                    stack.leases,
                    stack.counters::nextTxnId,
                ),
                stack.jobs,
                ledgerVersion = stack.counters::peekTxnId,
            )

            // Every lot home to its own block, the way a rollback of a griefed region goes —
            // not all of it to one place, which is the case that never had the problem.
            val perRoot = RollbackTarget.PerRoot(roots.withIndex().associate { (i, lot) -> lot to block(i, 64, 0) })

            val began = TimeSource.Monotonic.markNow()
            coordinator.run(RollbackJobId(1), roots, target = perRoot)
            return began.elapsedNow().inWholeNanoseconds / 1000.0 / steps
        }
    }

    @Test
    fun `the cost of a step does not grow with how many steps there are`(@TempDir dir: Path) = runTest {
        // Warmed first, so the small run is not paying for class loading the large one skips
        perStepMicros(dir.resolve("warmup"), 500)

        val small = perStepMicros(dir.resolve("small"), 1_000)
        val large = perStepMicros(dir.resolve("large"), 4_000)

        assertTrue(
            large < small * 2.5,
            "four times the steps cost ${"%.1f".format(large / small)}x per step " +
                    "($small us -> $large us) — that is the escrow drain gone quadratic again",
        )
    }
}
