package com.tracel.tests.capture

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.capture.CaptureCoordinator
import com.tracel.engine.ledger.Ingredient
import com.tracel.engine.ledger.Product
import com.tracel.engine.log.InMemoryTransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.itemEntity
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.LedgerHarness
import com.tracel.tests.support.assertFails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CaptureCoordinatorTest {
    @Test
    fun `a matched move updates the ledger and logs one transaction`() = runTest {
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
    fun `an unmatched gain mints, and the mint is what gets logged`() = runTest {
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
    fun `recordCraft consumes ingredients and produces the output as one transaction`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val stick = ItemKey("minecraft:stick")
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        world.ledger.mint(steve, diamond, Quantity(2), world.nextTxn())

        val transaction = coordinator.recordCraft(
            ingredients = listOf(Ingredient(steve, diamond, Quantity(2))),
            product = Product(steve, stick, Quantity(4)),
            epochMillis = 1_000L,
            causedBy = steve,
        )

        assertEquals(CauseKind.CRAFT, transaction.cause)
        assertEquals(0L, world.ledger.totalAt(steve, diamond)?.raw ?: 0L)
        assertEquals(4L, world.ledger.totalAt(steve, stick)?.raw)

        val transformIn = transaction.flows.single { it.kind == FlowKind.TRANSFORM_IN }
        assertEquals(diamond, transformIn.itemKey)
        assertEquals(steve, transformIn.source)
        assertEquals(HolderId.Sink(SinkKind.CRAFT_CONSUME), transformIn.destination)

        val transformOut = transaction.flows.single { it.kind == FlowKind.TRANSFORM_OUT }
        assertEquals(stick, transformOut.itemKey)
        assertEquals(HolderId.Source(SourceKind.CRAFT), transformOut.source)
        assertEquals(steve, transformOut.destination)

        assertEquals(transaction, log.find(transaction.id))
    }

    @Test
    fun `a craft carries the place it happened, or no radius query can ever find it`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val stick = ItemKey("minecraft:stick")
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }
        val bench = BlockPos(WorldId(java.util.UUID(0L, 7L)), 12, 64, -30)

        world.ledger.mint(steve, diamond, Quantity(2), world.nextTxn())

        val transaction = coordinator.recordCraft(
            ingredients = listOf(Ingredient(steve, diamond, Quantity(2))),
            product = Product(steve, stick, Quantity(4)),
            epochMillis = 1_000L,
            causedBy = steve,
            at = bench,
        )

        assertEquals(bench, transaction.at)
        assertEquals(bench, log.find(transaction.id)?.at)
    }

    @Test
    fun `recordDirect bypasses the balancer, using exactly the sink kind the caller chose`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val ground = itemEntity(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        world.ledger.mint(ground, diamond, Quantity(5), world.nextTxn())

        val flow = Flow(diamond, Quantity(5), ground, HolderId.Sink(SinkKind.DESPAWN), FlowKind.BURN)
        val transaction = coordinator.recordDirect(listOf(flow), epochMillis = 1_000L, cause = CauseKind.WORLD, causedBy = null)

        checkNotNull(transaction)
        assertEquals(CauseKind.WORLD, transaction.cause)
        assertEquals(flow, transaction.flows.single())
        assertEquals(0L, world.ledger.totalAt(ground, diamond)?.raw ?: 0L)
        assertEquals(transaction, log.find(transaction.id))
    }

    @Test
    fun `recordDirect with no flows records nothing`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val transaction = coordinator.recordDirect(emptyList(), epochMillis = 1_000L, cause = CauseKind.WORLD, causedBy = null)

        assertNull(transaction)
    }

    @Test
    fun `an empty diff records nothing - no transaction, no log entry`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val transaction = coordinator.record(emptyList(), epochMillis = 1_000L, cause = CauseKind.UNKNOWN, causedBy = null)

        assertNull(transaction)
    }

    @Test
    fun `a capture that cannot fully apply applies none of it, and logs nothing`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        // Steve gains stone out of nowhere (a satisfiable "MINT") in the very same capture that
        // says a chest lost diamonds the ledger never knew it had (an unsatisfiable "BURN").
        val stone = ItemKey("minecraft:stone")
        val deltas = listOf(InventoryDelta(steve, stone, 3L), InventoryDelta(chest, diamond, -5L))

        assertFails<IllegalStateException> {
            coordinator.record(deltas, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve)
        }

        assertNull(world.ledger.totalAt(steve, stone), "the satisfiable half must not have been applied on its own")
        assertEquals(0L, world.ledger.census(stone))
        assertTrue(log.all().isEmpty(), "nothing may be applied without a transaction recording it")
    }

    @Test
    fun `recordDirect refuses a flow set it cannot fully apply, leaving the ledger untouched`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val ground = itemEntity(7)
        val steve = player(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        world.ledger.mint(ground, diamond, Quantity(2), world.nextTxn())

        // Two flows draining the same ground item: the first is fine, the second overdraws it
        val flows = listOf(
            Flow(diamond, Quantity(2), ground, steve, FlowKind.MOVE),
            Flow(diamond, Quantity(1), ground, HolderId.Sink(SinkKind.DESPAWN), FlowKind.BURN),
        )

        assertFails<IllegalStateException> {
            coordinator.recordDirect(flows, epochMillis = 1_000L, cause = CauseKind.WORLD, causedBy = null)
        }

        assertEquals(2L, world.ledger.totalAt(ground, diamond)?.raw, "the ground item keeps everything it had")
        assertNull(world.ledger.totalAt(steve, diamond), "the first flow must not have landed on its own")
        assertTrue(log.all().isEmpty())
    }

    @Test
    fun `a flow drawing on what an earlier flow in the same capture deposited is allowed`() = runTest {
        // The check simulates the flows in order rather than only summing per source, so a
        // legitimate hand-off inside one transaction is not mistaken for an overdraw.
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val ground = itemEntity(7)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        world.ledger.mint(chest, diamond, Quantity(5), world.nextTxn())

        val flows = listOf(
            Flow(diamond, Quantity(5), chest, steve, FlowKind.MOVE),
            Flow(diamond, Quantity(5), steve, ground, FlowKind.MOVE),
        )

        val transaction = coordinator.recordDirect(flows, epochMillis = 1_000L, cause = CauseKind.WORLD, causedBy = null)

        checkNotNull(transaction)
        assertEquals(5L, world.ledger.totalAt(ground, diamond)?.raw)
        assertNull(world.ledger.totalAt(steve, diamond))
    }

    @Test
    fun `a move out of a holder the ledger never credited mints the shortfall rather than failing`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val ground = itemEntity(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val deltas = listOf(InventoryDelta(steve, diamond, -2L), InventoryDelta(ground, diamond, 2L))

        val transaction = coordinator.record(
            deltas, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve, mintShortfall = { it is HolderId.Player },
        )

        checkNotNull(transaction)
        assertEquals(2L, world.ledger.totalAt(ground, diamond)?.raw, "the diamonds end up on the floor")
        assertNull(world.ledger.totalAt(steve, diamond), "and none of them stay on Steve")
        val mint = transaction.flows.first()
        assertEquals(FlowKind.MINT, mint.kind, "the mint is logged, and logged before the move that needs it")
        assertEquals(SourceKind.UNATTRIBUTED, (mint.source as HolderId.Source).kind)
        assertEquals(FlowKind.MOVE, transaction.flows.last().kind)
    }

    @Test
    fun `a shortfall at a holder the policy excludes is refused, not minted`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val ground = itemEntity(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val deltas = listOf(InventoryDelta(ground, diamond, -2L), InventoryDelta(steve, diamond, 2L))

        assertFails<IllegalStateException> {
            coordinator.record(
                deltas, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve,
                mintShortfall = { it is HolderId.Player },
            )
        }
        assertNull(world.ledger.totalAt(steve, diamond), "nothing was handed over")
    }

    @Test
    fun `without the flag that same move is still refused outright`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val ground = itemEntity(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val deltas = listOf(InventoryDelta(steve, diamond, -2L), InventoryDelta(ground, diamond, 2L))

        assertFails<IllegalStateException> {
            coordinator.record(deltas, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve)
        }
    }

    @Test
    fun `a mint and a withdrawal of the same material in one transaction both apply`() = runTest {
        val world = LedgerHarness()
        val log = InMemoryTransactionLog()
        val bush = block(10, 64, 10)
        val steve = player(1)
        var nextSeqRaw = 1L
        val coordinator = CaptureCoordinator(world.ledger, log, world::nextTxn) { Seq(nextSeqRaw++) }

        val flows = listOf(
            Flow(diamond, Quantity(3), HolderId.Source(SourceKind.WORLDGEN), bush, FlowKind.MINT),
            Flow(diamond, Quantity(3), bush, steve, FlowKind.MOVE),
        )

        val transaction = coordinator.recordDirect(flows, epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve)

        assertEquals(3L, world.ledger.totalAt(steve, diamond)?.raw, "the material ends up in the hand")
        assertNull(world.ledger.totalAt(bush, diamond), "and none of it is left behind in the plant")
        assertEquals(1, log.all().size, "one transaction, not a mint and a move filed separately")
        assertEquals(2, transaction?.flows?.size)
    }
}
