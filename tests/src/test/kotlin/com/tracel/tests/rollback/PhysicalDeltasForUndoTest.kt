package com.tracel.tests.rollback

import com.tracel.engine.ledger.craft.Product
import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.involution.plan.RemakeInput
import com.tracel.engine.rollback.involution.plan.RemakeOutput
import com.tracel.engine.rollback.involution.plan.physicalDeltasForUndo
import com.tracel.model.id.LotId
import com.tracel.model.id.Quantity
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PhysicalDeltasForUndoTest {
    @Test
    fun `a Return step nets a negative delta at from and a positive one at to`() = runTest {
        val chest = block(0, 64, 0)
        val steve = player(1)

        val steps = listOf(InvolutionStep.Return(diamond, Quantity(5), from = steve, to = chest, lotId = LotId(1)))

        assertEquals(mapOf(steve to mapOf(diamond to -5L), chest to mapOf(diamond to 5L)), physicalDeltasForUndo(steps))
    }

    @Test
    fun `a Retract step only debits from, no physical destination`() = runTest {
        val steve = player(1)

        val steps = listOf(InvolutionStep.Retract(diamond, Quantity(3), from = steve))

        assertEquals(mapOf(steve to mapOf(diamond to -3L)), physicalDeltasForUndo(steps))
    }

    @Test
    fun `a Retract of a stack a Return already emptied is not a second take`() = runTest {
        val chest = block(0, 64, 0)
        val drop = com.tracel.tests.support.Fixtures.itemEntity(3)
        val steps = listOf(
            InvolutionStep.Return(diamond, Quantity(64), from = chest, to = drop, lotId = LotId(1)),
            InvolutionStep.Retract(diamond, Quantity(64), from = chest, originalLot = LotId(2)),
        )
        assertEquals(
            mapOf(chest to mapOf(diamond to -64L), drop to mapOf(diamond to 64L)),
            physicalDeltasForUndo(steps),
        )
    }

    @Test
    fun `a Remake step debits every ingredient and credits the product, all at the same holder`() = runTest {
        val steve = player(1)

        val steps = listOf(
            InvolutionStep.Remake(
                outputs = listOf(RemakeOutput(LotId(2), Quantity(1), steve)),
                product = Product(steve, diamondBlock, Quantity(1)),
                inputs = listOf(RemakeInput(LotId(1), diamond, Quantity(9))),
            )
        )

        assertEquals(mapOf(steve to mapOf(diamond to -9L, diamondBlock to 1L)), physicalDeltasForUndo(steps))
    }
}
