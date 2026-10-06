package com.tracel.storage.ports.ledger

import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class PendingDeliveryRepositoryTest {
    @Test
    fun `pending deliveries are claimed exactly once`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val steve = UUID(0, 1)
            stack.pending.enqueueAll(steve, mapOf(diamond to 5L, diamondBlock to -1L), RollbackJobId(1), 1000)

            val claimed = stack.pending.claimFor(steve)
            assertEquals(mapOf(diamond to 5L, diamondBlock to -1L), claimed.associate { it.itemKey to it.delta })
            assertEquals(emptyList<Any>(), stack.pending.claimFor(steve), "a claim empties the queue")
        }
    }

    @Test
    fun `one player's deliveries are not another's`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.pending.enqueueAll(UUID(0, 1), mapOf(diamond to 5L), RollbackJobId(1), 1000)
            stack.pending.enqueueAll(UUID(0, 2), mapOf(diamond to 9L), RollbackJobId(1), 1000)
            assertEquals(listOf(5L), stack.pending.claimFor(UUID(0, 1)).map { it.delta })
            assertEquals(listOf(9L), stack.pending.claimFor(UUID(0, 2)).map { it.delta })
        }
    }
}
