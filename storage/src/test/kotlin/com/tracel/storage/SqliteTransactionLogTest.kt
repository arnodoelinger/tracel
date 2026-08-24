package com.tracel.storage

import com.tracel.annotations.CauseKind
import com.tracel.engine.log.LookupFilter
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.Quantity
import com.tracel.model.id.Seq
import com.tracel.model.id.TxnId
import com.tracel.model.transaction.Transaction
import com.tracel.storage.log.SqliteTransactionLog
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import com.tracel.tests.support.Fixtures.player
import com.tracel.tests.support.assertFails
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteTransactionLogTest {
    @Test
    fun `an appended transaction round-trips exactly, including its flows`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.storage)
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
    fun `an unknown transaction id is not found`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            assertNull(SqliteTransactionLog(db.storage).find(TxnId(1)))
        }
    }

    @Test
    fun `the log refuses to append the same transaction id twice`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.storage)
            val txn = Transaction(TxnId(1), Seq(1), 0L, CauseKind.UNKNOWN, null, emptyList())
            log.append(txn)

            assertFails<IllegalStateException> { log.append(txn) }
        }
    }

    @Test
    fun `a transaction survives closing and reopening the same file`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("db.sqlite")
        val chest = block(0, 64, 0)
        val txn = Transaction(TxnId(1), Seq(1), 42L, CauseKind.HOPPER, null, listOf(Flow(diamond, Quantity(1), chest, chest, FlowKind.MOVE)))

        TracelDatabase.open(path).use { db -> SqliteTransactionLog(db.storage).append(txn) }

        TracelDatabase.open(path).use { db ->
            assertEquals(txn, SqliteTransactionLog(db.storage).find(txn.id))
        }
    }

    @Test
    fun `query filters by holder, material, cause and time, newest first`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.storage)
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
            assertEquals(listOf(txn1), log.query(LookupFilter(holders = setOf(steve, griefer), excludedHolders = setOf(griefer))))
            assertEquals(listOf(txn3, txn1), log.query(LookupFilter(material = "minecraft:diamond")))
            assertEquals(listOf(txn2), log.query(LookupFilter(causes = setOf(CauseKind.EXPLOSION))))
            assertEquals(listOf(txn3, txn2), log.query(LookupFilter(since = 2_000L)))
            assertEquals(listOf(txn2), log.query(LookupFilter(limit = 1, since = 1_500L, until = 2_500L)))
        }
    }

    @Test
    fun `an unknown holder or material matches nothing instead of everything`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.storage)
            log.append(Transaction(TxnId(1), Seq(1), 0L, CauseKind.UNKNOWN, null, emptyList()))

            assertEquals(emptyList<Transaction>(), log.query(LookupFilter(holders = setOf(player(99)))))
            assertEquals(emptyList<Transaction>(), log.query(LookupFilter(material = "minecraft:never_seen")))
        }
    }

    @Test
    fun `offset pages through newest-first results without skipping or repeating`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val log = SqliteTransactionLog(db.storage)
            val chest = block(0, 64, 0)
            val steve = player(1)
            val txns = (1..5).map { i ->
                Transaction(TxnId(i.toLong()), Seq(i.toLong()), i * 1_000L, CauseKind.HOPPER, null, listOf(Flow(diamond, Quantity(1), chest, steve, FlowKind.MOVE)))
            }
            txns.forEach { log.append(it) }

            val page1 = log.query(LookupFilter(limit = 2, offset = 0))
            val page2 = log.query(LookupFilter(limit = 2, offset = 2))
            val page3 = log.query(LookupFilter(limit = 2, offset = 4))

            assertEquals(listOf(txns[4], txns[3]), page1)
            assertEquals(listOf(txns[2], txns[1]), page2)
            assertEquals(listOf(txns[0]), page3)
        }
    }
}
