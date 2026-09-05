package com.tracel.storage

import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.ownership.LeaseAcquisition
import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PropertyEquivalenceTest {
    @Test
    fun `restore - census after rollback matches the census at the traced checkpoint`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val chest = block(0, 64, 0)
            val p1 = player(1)
            val p2 = player(2)
            val p3 = player(3)

            val root = stack.ledger.mint(chest, diamond, Quantity(20), stack.counters.nextTxnId())
            val checkpointCensus = stack.ledger.census(diamond)

            stack.ledger.move(chest, p1, diamond, Quantity(12), stack.counters.nextTxnId())
            stack.ledger.move(p1, p2, diamond, Quantity(5), stack.counters.nextTxnId())
            stack.ledger.move(chest, p3, diamond, Quantity(8), stack.counters.nextTxnId())
            stack.ledger.move(p2, p3, diamond, Quantity(2), stack.counters.nextTxnId())
            stack.ledger.move(p3, p1, diamond, Quantity(1), stack.counters.nextTxnId())

            val plan = RollbackPlanner(stack.repo, { true }).plan(listOf(root.id))
            val lease = (stack.leases.acquire(RollbackJobId(1), plan.touchedLots) as LeaseAcquisition.Granted).lease
            JournalExecutor(
                RollbackExecutor(stack.ledger, stack.log, stack.counters::nextSeq),
                stack.journal,
                stack.leases,
                stack.counters::nextTxnId,
            ).execute(lease, plan, target = RollbackTarget.Uniform(chest))

            assertEquals(checkpointCensus, stack.ledger.census(diamond))
            assertEquals(20L, stack.ledger.totalAt(chest, diamond)?.raw)
        }
    }

    @Test
    fun `determinism - identical operation sequences produce identical plans on fresh stores`(@TempDir dir: Path) =
        runTest {
            suspend fun scenario(path: Path): RollbackPlan = Stack(path).use { stack ->
                val chest = block(0, 64, 0)
                val p1 = player(1)
                val p2 = player(2)

                val root = stack.ledger.mint(chest, diamond, Quantity(10), stack.counters.nextTxnId())
                stack.ledger.move(chest, p1, diamond, Quantity(6), stack.counters.nextTxnId())
                stack.ledger.move(chest, p2, diamond, Quantity(4), stack.counters.nextTxnId())
                stack.ledger.move(p1, p2, diamond, Quantity(2), stack.counters.nextTxnId())
                RollbackPlanner(stack.repo, { true }).plan(listOf(root.id))
            }

            assertEquals(
                scenario(dir.resolve("first")),
                scenario(dir.resolve("second")),
                "two fresh stores running the identical sequence must assign identical ids",
            )
        }

    @Test
    fun `conservation - a long random walk never creates or loses a unit`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val holders = listOf(block(0, 64, 0), player(1), player(2), player(3), block(10, 64, 10))
            stack.ledger.mint(holders[0], diamond, Quantity(1_000), stack.counters.nextTxnId())

            val random = java.util.Random(20260824)
            repeat(400) {
                val from = holders[random.nextInt(holders.size)]
                val to = holders[random.nextInt(holders.size)]
                if (from == to) return@repeat
                val available = stack.ledger.totalAt(from, diamond)?.raw ?: return@repeat
                val take = 1L + random.nextInt(available.toInt().coerceAtLeast(1))
                if (take > available) return@repeat
                stack.ledger.move(from, to, diamond, Quantity(take), stack.counters.nextTxnId())
            }

            assertEquals(1_000L, stack.ledger.census(diamond), "400 moves must not have invented or eaten a diamond")
        }
    }
}
