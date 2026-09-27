package com.tracel.tests.property

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UnmakeIntoSinkTest {
    private val steve = player(1)

    @Test
    fun `a burned output is compensated, not unmade`() = runTest {
        val world = LedgerHarness()

        val root = world.ledger.mint(steve, diamond, Quantity(9), world.nextTxn())
        world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )

        val intact = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertTrue(
            intact.steps.any { it is RollbackStep.Unmake },
            "while the block exists, unmaking it is the way back"
        )

        world.ledger.burn(steve, diamondBlock, Quantity(1), SinkKind.UNATTRIBUTED, world.nextTxn())

        val burned = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        assertTrue(
            burned.steps.none { it is RollbackStep.Unmake },
            "there is no output left to take apart, so no unmake is planned",
        )
        assertEquals(
            listOf(RollbackStep.Mint(root.id, Quantity(9), SinkKind.UNATTRIBUTED)),
            burned.steps,
            "the ingredients are compensated instead, once",
        )
    }
}
