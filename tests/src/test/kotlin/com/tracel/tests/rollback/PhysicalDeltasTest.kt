package com.tracel.tests.rollback

import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.rollback.plan.*
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
        val crafted = world.ledger.craft(
            listOf(Ingredient(steve, diamond, Quantity(9))),
            Product(steve, diamondBlock, Quantity(1)),
            world.nextTxn(),
        )

        val plan = RollbackPlan(
            listOf(
                RollbackStep.Unmake(
                    listOf(UnmadeOutput(crafted.output.id, steve)),
                    listOf(LotContribution(ingredientLot.id, Quantity(9))),
                    world.nextTxn(),
                    steve
                )
            )
        )

        assertEquals(
            mapOf(steve to mapOf(diamondBlock to -1L, diamond to 9L)),
            physicalDeltas(plan, bob, world.ledger),
        )
    }

    @Test
    fun `a Mint of a key a Take already delivers is not a second physical stack`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val ground = com.tracel.tests.support.Fixtures.itemEntity(9)
        val live = world.ledger.mint(chest, diamond, Quantity(64), world.nextTxn())
        world.ledger.move(chest, ground, diamond, Quantity(64), world.nextTxn())
        val burned = world.ledger.mint(chest, diamond, Quantity(64), world.nextTxn())
        world.ledger.burn(chest, diamond, Quantity(64), SinkKind.UNATTRIBUTED, world.nextTxn())

        val plan = RollbackPlan(
            listOf(
                RollbackStep.Take(live.id, Quantity(64), ground),
                RollbackStep.Mint(burned.id, Quantity(64), SinkKind.UNATTRIBUTED),
            )
        )

        assertEquals(
            mapOf(ground to mapOf(diamond to -64L), chest to mapOf(diamond to 64L)),
            physicalDeltas(plan, chest, world.ledger),
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
