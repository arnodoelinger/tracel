package com.tracel.tests.log

import com.tracel.model.transaction.CauseKind
import com.tracel.engine.log.InMemoryTransactionLog
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.id.WorldId
import com.tracel.model.transaction.Transaction
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.assertFails
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.*

class InMemoryTransactionLogTest {
    @Test
    fun `an appended transaction round-trips exactly`() = runTest {
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val txn = Transaction(
            TxnId(1),
            Seq(1),
            epochMillis = 1_000L,
            cause = CauseKind.PLAYER_ACTION,
            causedBy = steve,
            flows = listOf(Flow(diamond, Quantity(5), chest, steve, FlowKind.MOVE)),
        )

        log.append(txn)

        assertEquals(txn, log.find(txn.id))
    }

    @Test
    fun `an unknown transaction id is not found`() = runTest {
        assertNull(InMemoryTransactionLog().find(TxnId(1)))
    }

    @Test
    fun `the log refuses to append the same transaction id twice`() = runTest {
        val log = InMemoryTransactionLog()
        val txn = Transaction(TxnId(1), Seq(1), 0L, CauseKind.UNKNOWN, null, emptyList())
        log.append(txn)

        assertFails<IllegalStateException> { log.append(txn) }
    }

    @Test
    fun `query filters by holder, material, cause and time, newest first`() = runTest {
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val griefer = player(2)

        val txn1 = Transaction(
            TxnId(1), Seq(1), epochMillis = 1_000L, cause = CauseKind.PLAYER_ACTION, causedBy = steve,
            flows = listOf(Flow(diamond, Quantity(5), chest, steve, FlowKind.MOVE)),
        )
        val txn2 = Transaction(
            TxnId(2), Seq(2), epochMillis = 2_000L, cause = CauseKind.EXPLOSION, causedBy = griefer,
            flows = listOf(Flow(diamondBlock, Quantity(1), chest, HolderId.Sink(SinkKind.UNATTRIBUTED), FlowKind.BURN)),
        )
        val txn3 = Transaction(
            TxnId(3), Seq(3), epochMillis = 3_000L, cause = CauseKind.HOPPER, causedBy = null,
            flows = listOf(Flow(diamond, Quantity(2), chest, chest, FlowKind.MOVE)),
        )
        listOf(txn1, txn2, txn3).forEach { log.append(it) }

        assertEquals(listOf(txn2, txn1), log.query(LookupFilter(holders = setOf(steve, griefer))))
        assertEquals(
            listOf(txn1),
            log.query(LookupFilter(holders = setOf(steve, griefer), excludedHolders = setOf(griefer)))
        )
        assertEquals(listOf(txn3, txn1), log.query(LookupFilter(material = "minecraft:diamond")))
        assertEquals(listOf(txn2), log.query(LookupFilter(causes = setOf(CauseKind.EXPLOSION))))
        assertEquals(listOf(txn3, txn2), log.query(LookupFilter(since = 2_000L)))
        assertEquals(listOf(txn2), log.query(LookupFilter(limit = 1, since = 1_500L, until = 2_500L)))
        assertEquals(
            listOf(txn3, txn1),
            log.query(LookupFilter(excludedCauses = setOf(CauseKind.EXPLOSION))),
        )
    }

    @Test
    fun `a rollback's own transactions are invisible to every query`() = runTest {
        val log = InMemoryTransactionLog()
        val steve = player(1)
        val chest = block(0, 64, 0)

        fun row(seq: Long, cause: CauseKind) = Transaction(
            TxnId(seq),
            Seq(seq),
            seq * 1_000L,
            cause,
            steve,
            listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE)),
        )

        val theft = row(1, CauseKind.PLAYER_ACTION)
        listOf(theft, row(2, CauseKind.ROLLBACK), row(3, CauseKind.INVOLUTION)).forEach { log.append(it) }

        assertEquals(listOf(theft), log.query(LookupFilter()), "an unfiltered query sees the theft and nothing else")
        assertEquals(listOf(theft), log.query(LookupFilter(holders = setOf(steve))))
        assertEquals(emptyList<Transaction>(), log.query(LookupFilter(causes = setOf(CauseKind.ROLLBACK))))
        assertEquals(emptyList<Transaction>(), log.query(LookupFilter(causes = setOf(CauseKind.INVOLUTION))))
    }

    @Test
    fun `a region filter keeps transactions whose chests sit inside it`() = runTest {
        val log = InMemoryTransactionLog()
        val world = WorldId(UUID(0L, 1L))
        val here = block(3, 64, 5)
        val there = block(1608, 64, 1608)
        val steve = player(1)
        log.append(
            Transaction(
                TxnId(1),
                Seq(1),
                100,
                CauseKind.PLAYER_ACTION,
                steve,
                listOf(Flow(diamond, Quantity(1), here, steve, FlowKind.MOVE))
            )
        )
        log.append(
            Transaction(
                TxnId(2),
                Seq(2),
                200,
                CauseKind.PLAYER_ACTION,
                steve,
                listOf(Flow(diamond, Quantity(1), there, steve, FlowKind.MOVE))
            )
        )

        val nearby = LookupRegion(world, 0, 0, 0, 0)
        assertEquals(
            listOf(Seq(1)),
            log.query(LookupFilter(holders = setOf(steve), region = nearby)).map { it.seq },
        )
    }

    @Test
    fun `offset pages through newest-first results without skipping or repeating`() = runTest {
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val txns = (1..5).map { i ->
            Transaction(
                TxnId(i.toLong()),
                Seq(i.toLong()),
                i * 1_000L,
                CauseKind.HOPPER,
                null,
                listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE))
            )
        }
        txns.forEach { log.append(it) }

        val page1 = log.query(LookupFilter(limit = 2, offset = 0))
        val page2 = log.query(LookupFilter(limit = 2, offset = 2))
        val page3 = log.query(LookupFilter(limit = 2, offset = 4))

        assertEquals(listOf(txns[4], txns[3]), page1)
        assertEquals(listOf(txns[2], txns[1]), page2)
        assertEquals(listOf(txns[0]), page3)
    }

    @Test
    fun `bookkeeping is findable by id but invisible to lookup`() = runTest {
        val log = InMemoryTransactionLog()
        val chest = block(0, 64, 0)
        val steve = player(1)
        val original = Transaction(
            TxnId(1),
            Seq(1),
            100,
            CauseKind.PLAYER_ACTION,
            steve,
            listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE))
        )
        val rollback = Transaction(
            TxnId(2),
            Seq(2),
            200,
            CauseKind.ROLLBACK,
            null,
            listOf(Flow(diamond, Quantity(1), steve, chest, FlowKind.MOVE))
        )
        val undo = Transaction(
            TxnId(3),
            Seq(3),
            300,
            CauseKind.INVOLUTION,
            null,
            listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE))
        )
        listOf(original, rollback, undo).forEach { log.append(it) }

        assertEquals(rollback, log.find(TxnId(2)))
        assertEquals(listOf(original), log.query(LookupFilter(limit = Int.MAX_VALUE)))
    }
}
