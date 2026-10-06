package com.tracel.storage.ports.ops

import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CountersTest {
    @Test
    fun `counters never hand out an id twice, across a restart`(@TempDir dir: Path) = runTest {
        val first = Stack(dir).use { stack -> (1..10).map { stack.counters.nextTxnId().raw } }
        val second = Stack(dir).use { stack -> (1..10).map { stack.counters.nextTxnId().raw } }
        assertEquals(20, (first + second).distinct().size, "a reserved block is forfeited on restart, never reused")
        assertTrue(second.min() > first.max(), "ids only ever go forwards")
    }

    @Test
    fun `different counters do not share a sequence`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            assertEquals(1L, stack.counters.nextTxnId().raw)
            assertEquals(Counters.SEQ_BASE, stack.counters.nextSeq().raw)
            assertEquals(1L, stack.counters.nextLotId().raw)
        }
    }
}
