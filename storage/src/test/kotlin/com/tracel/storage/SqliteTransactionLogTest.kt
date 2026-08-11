package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.log.SqliteTransactionLog
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteTransactionLogTest {
    @Test
    fun `an appended transaction round-trips exactly, including its flows`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.exposed)
            val chest = block(0, 64, 0)
            val steve = player(1)
            val txn = Transaction(
                TxnId(1),
                Seq(1),
                epochMillis = 1_000L,
                cause = CauseKind.PLAYER_ACTION,
                causedBy = steve,
                flows = listOf(
                    Flow(diamond, Quantity(5), chest, steve, FlowKind.MOVE),
                    Flow(diamond, Quantity(1), steve, chest, FlowKind.MOVE),
                ),
            )

            log.append(txn)

            assertEquals(txn, log.find(txn.id))
        }
    }

    @Test
    fun `an unknown transaction id is not found`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            assertNull(SqliteTransactionLog(db.exposed).find(TxnId(1)))
        }
    }

    @Test
    fun `the log refuses to append the same transaction id twice`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.exposed)
            val txn = Transaction(TxnId(1), Seq(1), 0L, CauseKind.UNKNOWN, null, emptyList())
            log.append(txn)

            assertThrows(IllegalStateException::class.java) { log.append(txn) }
        }
    }

    @Test
    fun `a transaction survives closing and reopening the same file`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val chest = block(0, 64, 0)
        val txn = Transaction(TxnId(1), Seq(1), 42L, CauseKind.HOPPER, null, listOf(Flow(diamond, Quantity(1), chest, chest, FlowKind.MOVE)))

        TracelDatabase.open(path).use { db -> SqliteTransactionLog(db.exposed).append(txn) }

        TracelDatabase.open(path).use { db ->
            assertEquals(txn, SqliteTransactionLog(db.exposed).find(txn.id))
        }
    }
}
