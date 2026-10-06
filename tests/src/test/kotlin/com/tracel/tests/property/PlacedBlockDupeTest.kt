package com.tracel.tests.property

import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.holder.HolderId
import com.tracel.model.item.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.placedBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlacedBlockDupeTest {
    private val chest = block(0, 64, 0)
    private val placed = placedBlock(1, 64, 0)
    private val steve = player(1)

    @Test
    fun `material alone will not take a block that nothing is going to break`() = runTest {
        val world = LedgerHarness()

        val root = world.ledger.mint(steve, diamond, Quantity(64), world.nextTxn())
        world.ledger.move(steve, placed, diamond, Quantity(1), world.nextTxn())
        world.ledger.move(steve, chest, diamond, Quantity(63), world.nextTxn())

        val whole = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertEquals(
            64L,
            whole.steps.filterIsInstance<RollbackStep.Take>().sumOf { it.quantity.raw },
            "with the structural half running, the placed one comes back with the rest",
        )
        assertTrue(
            whole.steps.any { it is RollbackStep.Take && it.holder is HolderId.PlacedBlock },
            "and it comes back by breaking the block",
        )

        val itemsOnly = RollbackPlanner(world.repo, { true }, structural = false).plan(listOf(root.id))
        assertEquals(
            63L,
            itemsOnly.steps.filterIsInstance<RollbackStep.Take>().sumOf { it.quantity.raw },
            "on its own it reclaims what is in the chest and leaves the block alone",
        )
        assertTrue(
            itemsOnly.steps.none { it is RollbackStep.Take && it.holder is HolderId.PlacedBlock },
            "nothing is planned against a block no structural step will break",
        )
    }
}
