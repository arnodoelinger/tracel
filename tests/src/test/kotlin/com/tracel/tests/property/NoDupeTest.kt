package com.tracel.tests.property

import com.tracel.engine.journal.InMemoryJournal
import com.tracel.engine.journal.JournalExecutor
import com.tracel.engine.rollback.RollbackExecutor
import com.tracel.engine.rollback.RollbackPlanner
import com.tracel.engine.rollback.RollbackStep
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.RollbackJobId
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * No dupe: `census_after = census_before + minted - burned`, for any
 * rollback — including the case that makes always-compensate risky in the
 * first place: some of the traced material was genuinely, physically
 * unrecoverable (burned in lava), so recovering it means minting a
 * replacement rather than taking anything back.
 */
class NoDupeTest {
    @Test
    fun `a burned portion is compensated exactly, not over- or under-minted`() = runTest {
        val world = LedgerHarness()
        val chest = block(0, 64, 0)
        val steve = player(1)

        val root = world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        world.ledger.move(chest, steve, diamond, Quantity(10), world.nextTxn())
        assertEquals(10L, world.ledger.census(diamond))

        // 4 of 10 burn in lava — the real material is now 4 less, and the census must honestly show this,
        // not hide it in the accounting.
        world.ledger.burn(steve, diamond, Quantity(4), SinkKind.LAVA, world.nextTxn())
        assertEquals(6L, world.ledger.census(diamond), "burning is a real loss, visible in the census")

        val plan = RollbackPlanner(world.repo, { true }).plan(listOf(root.id))
        val mintStep = plan.steps.filterIsInstance<RollbackStep.Mint>().single()
        assertEquals(4L, mintStep.quantity.raw, "compensation must match exactly what was actually lost")
        assertEquals(SinkKind.LAVA, mintStep.reason)

        JournalExecutor(RollbackExecutor(world.ledger), InMemoryJournal(), world.leases)
            .execute(world.acquireLease(RollbackJobId(1), plan), plan, restoreTo = chest, txn = world.nextTxn())

        // 6 remaining in the chest, 4 newly minted to compensate for the burned ones — census is back to 10
        assertEquals(10L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(10L, world.ledger.census(diamond), "I4: mints and burns are always accounted for in the census, never silently lost or duplicated")
    }
}
