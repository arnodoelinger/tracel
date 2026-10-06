package com.tracel.tests.rollback

import com.tracel.engine.rollback.apply.RollbackExecutor
import com.tracel.engine.rollback.journal.JournalExecutor
import com.tracel.engine.rollback.journal.memory.InMemoryJournal
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.*

class FrameCargoRestoreTest {
    private val steve = player(1)
    private val frame = HolderId.Entity(UUID(7L, 7L))
    private val ground = itemEntity(2)
    private val sword = ItemKey("minecraft:diamond_sword")

    private suspend fun LedgerHarness.rollback(job: Long, roots: List<LotId>, target: RollbackTarget) {
        val plan = RollbackPlanner(repo, { true }).plan(roots)
        JournalExecutor(RollbackExecutor(ledger, log, ::nextSeq), InMemoryJournal(), leases, ::nextTxn)
            .execute(acquireLease(RollbackJobId(job), plan), plan, target)
    }

    @Test
    fun `a window that also covers putting the sword in gives it back to the player, not the frame`() = runTest {
        val world = LedgerHarness()
        val lot = world.ledger.mint(steve, sword, Quantity(1), world.nextTxn())
        world.ledger.move(steve, frame, sword, Quantity(1), world.nextTxn())
        world.ledger.move(frame, ground, sword, Quantity(1), world.nextTxn())

        world.rollback(job = 1, roots = listOf(lot.id), target = RollbackTarget.PerRoot(mapOf(lot.id to steve)))

        assertEquals(
            1L,
            world.ledger.totalAt(steve, sword)?.raw,
            "the sword is back where the window started: the player"
        )
        assertNull(
            world.ledger.totalAt(frame, sword),
            "the frame is empty, because putting it in is part of what was undone"
        )
        assertNull(world.ledger.totalAt(ground, sword), "and nothing is left on the floor")
    }

    @Test
    fun `a window that covers only the break puts the sword back in the frame`() = runTest {
        val world = LedgerHarness()
        val lot = world.ledger.mint(steve, sword, Quantity(1), world.nextTxn())
        world.ledger.move(steve, frame, sword, Quantity(1), world.nextTxn())
        world.ledger.move(frame, ground, sword, Quantity(1), world.nextTxn())

        world.rollback(job = 1, roots = listOf(lot.id), target = RollbackTarget.PerRoot(mapOf(lot.id to frame)))

        assertEquals(1L, world.ledger.totalAt(frame, sword)?.raw, "the sword is back in the frame it was blown out of")
        assertNull(world.ledger.totalAt(steve, sword), "the player is not handed a second one")
        assertNull(world.ledger.totalAt(ground, sword), "and nothing is left on the floor")
    }
}
