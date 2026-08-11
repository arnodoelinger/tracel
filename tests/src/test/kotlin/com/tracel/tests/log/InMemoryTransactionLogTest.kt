package com.tracel.tests.log

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.InMemoryTransactionLog
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InMemoryTransactionLogTest {
    @Test
    fun `an appended transaction round-trips exactly`() {
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
    fun `an unknown transaction id is not found`() {
        assertNull(InMemoryTransactionLog().find(TxnId(1)))
    }

    @Test
    fun `the log refuses to append the same transaction id twice`() {
        val log = InMemoryTransactionLog()
        val txn = Transaction(TxnId(1), Seq(1), 0L, CauseKind.UNKNOWN, null, emptyList())
        log.append(txn)

        assertThrows(IllegalStateException::class.java) { log.append(txn) }
    }
}
