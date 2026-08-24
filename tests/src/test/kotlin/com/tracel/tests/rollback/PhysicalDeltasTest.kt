package com.tracel.tests.rollback

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.LotContribution
import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.RollbackStep
import com.tracel.engine.rollback.physicalDeltas
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PhysicalDeltasTest {
    @Test
    fun `a Take nets a negative delta at the source and a positive one at restoreTo`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val lot = world.ledger.mint(chest, diamond, Quantity(5), world.nextTxn())

        val plan = RollbackPlan(listOf(RollbackStep.Take(lot.id, Quantity(5), chest)))

        assertEquals(
            mapOf(chest to mapOf(diamond to -5L), steve to mapOf(diamond to 5L)),
            physicalDeltas(plan, steve, world.ledger),
        )
    }

    @Test
    fun `Mint and Debt steps only credit restoreTo, no physical source`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val bob = player(2)
        val lot = world.ledger.mint(chest, diamond, Quantity(3), world.nextTxn())

        val plan = RollbackPlan(
            listOf(
                RollbackStep.Mint(lot.id, Quantity(3), SinkKind.LAVA),
                RollbackStep.Debt(lot.id, Quantity(1), bob.uuid),
            )
        )

        assertEquals(mapOf(steve to mapOf(diamond to 4L)), physicalDeltas(plan, steve, world.ledger))
    }

    @Test
    fun `an Unmake step nets entirely at its own holder, not restoreTo`() = runTest {
        val world = LedgerHarness()
        val steve = player(1)
        val bob = player(2)
        val ingredientLot = world.ledger.mint(steve, diamond, Quantity(9), world.nextTxn())
        val outputLot = world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )

        val plan = RollbackPlan(
            listOf(RollbackStep.Unmake(outputLot.id, listOf(LotContribution(ingredientLot.id, Quantity(9))), world.nextTxn(), steve))
        )

        assertEquals(
            mapOf(steve to mapOf(diamondBlock to -1L, diamond to 9L)),
            physicalDeltas(plan, bob, world.ledger),
        )
    }

    @Test
    fun `rolling back to yourself nets to zero, not a spurious self-move`() = runTest {
        val world = LedgerHarness()
        val steve = player(1)
        val lot = world.ledger.mint(steve, diamond, Quantity(2), world.nextTxn())

        val plan = RollbackPlan(listOf(RollbackStep.Take(lot.id, Quantity(2), steve)))

        assertEquals(mapOf(steve to mapOf(diamond to 0L)), physicalDeltas(plan, steve, world.ledger))
    }
}
