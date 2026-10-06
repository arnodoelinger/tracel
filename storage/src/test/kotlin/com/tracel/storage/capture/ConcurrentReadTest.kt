package com.tracel.storage.capture

import com.tracel.engine.balance.InventoryDelta
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
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
        withContext(Dispatchers.Default) {
            Stack(dir).use { stack ->
                val chest = block(0, 64, 0)
                val steve = player(1)

                val lot = stack.mint(chest, diamond, 512)

                coroutineScope {
                    val readers = (1..8).map {
                        async {
                            repeat(200) {
                                assertEquals(diamond, stack.repo.lot(lot.id).itemKey)
                                stack.repo.currentHolderOf(lot.id)
                                stack.log.query(LookupFilter(limit = 16))
                            }
                        }
                    }

                    val writer = async {
                        repeat(200) { i ->
                            stack.capture.record(
                                listOf(
                                    InventoryDelta(chest, diamond, -1),
                                    InventoryDelta(steve, diamond, 1),
                                ),
                                1_000L + i,
                                CauseKind.PLAYER_ACTION,
                                steve as HolderId,
                            )
                        }
                    }

                    (readers + writer).awaitAll()
                }

                assertEquals(200L, stack.ledger.totalAt(steve, diamond)?.raw)
            }
        }
    }
}
