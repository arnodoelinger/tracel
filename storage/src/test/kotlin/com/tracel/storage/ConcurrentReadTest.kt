package com.tracel.storage

import com.tracel.model.transaction.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.Quantity
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConcurrentReadTest {
    @Test
    fun `many readers and a writer do not get in each other's way`(@TempDir dir: Path) = runTest {
        // Real threads: the thing under test is what
        // happens when several of them are inside the store at once.
        withContext(Dispatchers.Default) {
            Stack(dir).use { stack ->
                val chest = block(0, 64, 0)
                val steve = player(1)

                // Something for the readers to find, and to keep finding
                val lot = stack.ledger.mint(chest, diamond, Quantity(512), stack.counters.nextTxnId())

                coroutineScope {
                    val readers = (1..8).map {
                        async {
                            repeat(200) {
                                // Three different read shapes: a point get, a prefix scan, and a
                                // query that walks an index and decodes records.
                                assertEquals(diamond, stack.repo.lot(lot.id).itemKey)
                                stack.repo.currentHolderOf(lot.id)
                                stack.log.query(com.tracel.engine.log.LookupFilter(limit = 16))
                            }
                        }
                    }

                    val writer = async {
                        repeat(200) { i ->
                            stack.capture.record(
                                listOf(
                                    com.tracel.engine.balance.InventoryDelta(chest, diamond, -1),
                                    com.tracel.engine.balance.InventoryDelta(steve, diamond, 1),
                                ),
                                1_000L + i,
                                CauseKind.PLAYER_ACTION,
                                steve as HolderId,
                            )
                        }
                    }

                    (readers + writer).awaitAll()
                }

                // Every one of those moves really happened, and none of them was lost to a reader
                assertEquals(200L, stack.ledger.totalAt(steve, diamond)?.raw)
            }
        }
    }
}
