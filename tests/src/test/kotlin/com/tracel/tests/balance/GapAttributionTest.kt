package com.tracel.tests.balance

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.balance.TransactionBalancer
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GapAttributionTest {
    private val chest = block(0, 64, 0)

    @Test
    fun `an unmatched loss seen live is attributed to nothing in particular`() = runTest {
        val flows = TransactionBalancer().balance(listOf(InventoryDelta(chest, diamond, -4L)))

        assertEquals(HolderId.Sink(SinkKind.UNATTRIBUTED), flows.single().destination)
    }

    @Test
    fun `an unmatched loss found on first sight is attributed to the tracking gap`() = runTest {
        val flows = TransactionBalancer().balance(listOf(InventoryDelta(chest, diamond, -4L, fromGap = true)))

        assertEquals(HolderId.Sink(SinkKind.UNTRACKED_GAP), flows.single().destination)
    }

    @Test
    fun `an unmatched gain found on first sight is attributed to the tracking gap`() = runTest {
        val flows = TransactionBalancer().balance(listOf(InventoryDelta(chest, diamond, 4L, fromGap = true)))

        assertEquals(HolderId.Source(SourceKind.UNTRACKED_GAP), flows.single().source)
    }

    @Test
    fun `a gap-flagged delta that pairs off is an ordinary move`() = runTest {
        val steve = player(1)
        val flows = TransactionBalancer().balance(
            listOf(InventoryDelta(chest, diamond, 4L, fromGap = true), InventoryDelta(steve, diamond, -4L)),
        )

        assertEquals(1, flows.size)
        assertEquals(steve, flows.single().source)
        assertEquals(chest, flows.single().destination)
    }

    @Test
    fun `a partial pairing leaves only the surplus typed as a gap`() = runTest {
        val steve = player(1)
        val flows = TransactionBalancer().balance(
            listOf(InventoryDelta(chest, diamond, 10L, fromGap = true), InventoryDelta(steve, diamond, -4L)),
        )

        val minted = flows.single { it.source is HolderId.Source }
        assertEquals(HolderId.Source(SourceKind.UNTRACKED_GAP), minted.source)
        assertEquals(6L, minted.quantity.raw, "only the part with no counterpart is unexplained")
    }
}
