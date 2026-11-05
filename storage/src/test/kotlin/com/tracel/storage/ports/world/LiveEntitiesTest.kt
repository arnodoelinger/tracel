package com.tracel.storage.ports.world

import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class LiveEntitiesTest {
    @Test
    fun `an arrow in flight is still in flight after a restart`(@TempDir dir: Path) = runTest {
        val arrow = UUID(-1L, Long.MIN_VALUE)
        Stack(dir).use { stack -> LiveEntities(stack.storage).writeAll(mapOf(arrow to true)) }
        Stack(dir).use { stack -> assertEquals(setOf(arrow), LiveEntities(stack.storage).loadAll()) }
    }

    @Test
    fun `false forgets`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val live = LiveEntities(stack.storage)
            val (a, b) = UUID(0L, 1L) to UUID(0L, 2L)
            live.writeAll(mapOf(a to true, b to true))
            live.writeAll(mapOf(a to false))

            assertEquals(setOf(b), live.loadAll())
        }
    }
}
