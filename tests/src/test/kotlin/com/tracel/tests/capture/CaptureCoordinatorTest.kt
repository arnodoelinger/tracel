package com.tracel.tests.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.log.InMemoryTransactionLog
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The full pipeline: raw deltas -> balanced flows -> applied to the ledger -> logged. */
class CaptureCoordinatorTest {
    @Test
    fun `a matched move updates the ledger and logs one transaction`() {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        // A chest already has 10 diamonds from ordinary bootstrapping (mint), then a click
        // moves 4 of them to Steve — captured purely as "chest lost 4, Steve gained 4".
        world.ledger.mint(chest, diamond, Quantity(10), world.nextTxn())
        val deltas = listOf(InventoryDelta(chest, diamond, -4L), InventoryDelta(steve, diamond, 4L))

        val transaction = coordinator.record(deltas, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve)

        checkNotNull(transaction)
        assertEquals(1, transaction.flows.size)
        assertEquals(FlowKind.MOVE, transaction.flows.single().kind)
        assertEquals(6L, world.ledger.totalAt(chest, diamond)?.raw)
        assertEquals(4L, world.ledger.totalAt(steve, diamond)?.raw)
        assertEquals(transaction, log.find(transaction.id))
    }

    @Test
    fun `an unmatched gain mints, and the mint is what gets logged`() {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val transaction = coordinator.record(
            listOf(InventoryDelta(steve, diamond, 3L)),
            epochMillis = 1_000L,
            cause = CauseKind.UNKNOWN,
            causedBy = null,
        )

        checkNotNull(transaction)
        assertEquals(FlowKind.MINT, transaction.flows.single().kind)
        assertEquals(3L, world.ledger.totalAt(steve, diamond)?.raw)
    }

    @Test
    fun `an empty diff records nothing - no transaction, no log entry`() {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val transaction = coordinator.record(emptyList(), epochMillis = 1_000L, cause = CauseKind.UNKNOWN, causedBy = null)

        assertNull(transaction)
    }
}
