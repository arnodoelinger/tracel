package com.tracel.tests.property

import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.WorldQuery
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Determinism: the same sequence of operations always produces the same
 * lineage and the same rollback plan. Nothing in the ledger reads wall-clock
 * time or iterates an unordered collection where order would matter, so two
 * runs of an identical scenario must be indistinguishable — down to which
 * lot ids end up where.
 */
class DeterminismTest {
    @Test
    fun `identical operation sequences produce identical rollback plans`() = runTest {
        suspend fun runScenario(): RollbackPlan {
            val world = LedgerHarness()
            val chest = block(0, 64, 0)
            val p1 = player(1)
            val p2 = player(2)
            val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
            world.ledger.move(chest, p1, diamond, Quantity(6), world.nextTxn())
            world.ledger.move(chest, p2, diamond, Quantity(4), world.nextTxn())
            world.ledger.move(p1, p2, diamond, Quantity(2), world.nextTxn())
            return RollbackPlanner(world.repo, WorldQuery { true }).plan(listOf(root.id))
        }

        assertEquals(runScenario(), runScenario())
    }
}
