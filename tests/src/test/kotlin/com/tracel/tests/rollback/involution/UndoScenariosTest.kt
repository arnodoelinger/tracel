package com.tracel.tests.rollback.involution

import com.tracel.engine.rollback.involution.plan.InvolutionStep
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.lot.LotEdge
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UndoScenariosTest {
    private val world = LedgerHarness()
    private val chest = block(0, 64, 0)
    private val steve = player(1)

    @Test
    fun `undo does not abort when the destination was emptied since`() = runTest {
        val machine = block(1, 64, 0)
        val root = world.mint(chest, diamond, 10)
        world.move(chest, steve, diamond, 10)
        val job = world.rollback(listOf(root.id), chest).job
        world.move(chest, machine, diamond, 10)

        world.undo(job)

        assertEquals(10L, world.count(machine, diamond))
        assertEquals(0L, world.count(chest, diamond))
    }

    @Test
    fun `a vanished drop is still given back by emptying what the rollback filled`() = runTest {
        val drop = itemEntity(7)
        val root = world.mint(chest, diamond, 13)
        world.move(chest, drop, diamond, 13)
        val job = world.rollback(listOf(root.id), chest).job
        assertEquals(13L, world.count(chest, diamond))

        val returned = world.undo(job).steps.filterIsInstance<InvolutionStep.Return>().single()

        assertEquals(chest, returned.from)
        assertEquals(drop, returned.to)
        assertEquals(0L, world.count(chest, diamond), "the chest gives back what the rollback put there")
        assertEquals(13L, world.count(drop, diamond))
    }

    @Test
    fun `undoing an unmake re-crafts the destroyed item`() = runTest {
        world.mint(steve, diamond, 5)
        val looted = world.mint(chest, diamond, 4)
        world.move(chest, steve, diamond, 4)
        world.craft(steve, diamond, 9, diamondBlock, 1)

        val job = world.rollback(listOf(looted.id), chest).job
        assertEquals(4L, world.count(chest, diamond))
        assertEquals(0L, world.count(steve, diamondBlock))

        world.undo(job)

        assertEquals(1L, world.count(steve, diamondBlock), "the block is re-crafted")
        assertEquals(0L, world.count(steve, diamond), "all 9 diamonds went back into it")
        assertEquals(0L, world.count(chest, diamond), "the chest gave back the 4 it received")
        assertEquals(0L, world.census(diamond))
        assertEquals(1L, world.census(diamondBlock))

        val unmake = world.log.all()
            .single { it.cause == CauseKind.ROLLBACK && it.flows.any { f -> f.kind == FlowKind.TRANSFORM_IN } }
        assertEquals(diamondBlock, unmake.flows.single { it.kind == FlowKind.TRANSFORM_IN }.itemKey)
        val restored = unmake.flows.filter { it.kind == FlowKind.TRANSFORM_OUT }
        assertTrue(restored.all { it.itemKey == diamond })
        assertEquals(9L, restored.sumOf { it.quantity.raw })

        val remake = world.log.all()
            .single { it.cause == CauseKind.INVOLUTION && it.flows.any { f -> f.kind == FlowKind.TRANSFORM_OUT } }
        val consumed = remake.flows.filter { it.kind == FlowKind.TRANSFORM_IN }
        assertTrue(consumed.all { it.itemKey == diamond })
        assertEquals(9L, consumed.sumOf { it.quantity.raw })
        assertEquals(diamondBlock, remake.flows.single { it.kind == FlowKind.TRANSFORM_OUT }.itemKey)
    }

    @Test
    fun `undoing a compensation burns the minted lot and leaves older stock alone`() = runTest {
        val older = world.mint(chest, diamond, 20)
        val root = world.mint(steve, diamond, 10)
        world.burn(steve, diamond, 4)

        val applied = world.rollback(listOf(root.id), chest)
        val burned = applied.plan.steps.filterIsInstance<RollbackStep.Mint>().single().lotId
        val minted = world.repo.edgesFrom(burned).filterIsInstance<LotEdge.Compensate>().single().child
        assertNotNull(world.repo.placementOf(chest, minted), "the compensation is in the chest")

        world.undo(applied.job)

        assertNull(world.repo.placementOf(chest, minted), "the compensation is what the undo burned")
        assertEquals(20L, world.repo.placementOf(chest, older.id)?.remaining?.raw, "the chest's own stock is untouched")
        assertEquals(26L, world.census(diamond), "back where it started: 20 plus 10 minus the 4 burned")
        assertEquals(applied.plan, world.planner().plan(listOf(root.id)), "and the same window plans the same work")
    }
}
