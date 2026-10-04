package com.tracel.plugin.listener.support.guard

import com.tracel.model.id.WorldId
import com.tracel.model.world.BlockPos
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*

class SelfManagedWorldGuardTest {
    @Test
    fun `the flag covers only the call it wraps`() {
        val guard = SelfManagedWorldGuard()
        assertFalse(guard.isRestoring)
        guard.whileRestoring { assertTrue(guard.isRestoring) }
        assertFalse(guard.isRestoring, "and stops the moment the write returns — which water does not respect")
    }

    @Test
    fun `nesting restores the previous state rather than clearing it`() {
        val guard = SelfManagedWorldGuard()
        guard.whileRestoring {
            guard.whileRestoring { assertTrue(guard.isRestoring) }
            assertTrue(guard.isRestoring, "the outer restore is still running")
        }
        assertFalse(guard.isRestoring)
    }

    @Test
    fun `a read from before the restore wrote the cell is the restore's, not history`() {
        val guard = SelfManagedWorldGuard()
        val cell = BlockPos(WorldId(UUID(0L, 1L)), 0, 64, 0)
        guard.wrote(listOf(cell), nanos = 200L)
        assertTrue(guard.wroteSince(cell, readNanos = 100L))
    }

    @Test
    fun `water that moves in after the restore wrote the cell is history`() {
        val guard = SelfManagedWorldGuard()
        val cell = BlockPos(WorldId(UUID(0L, 1L)), 0, 64, 0)
        guard.wrote(listOf(cell), nanos = 200L)
        assertFalse(guard.wroteSince(cell, readNanos = 300L), "or the flow back into a restored room is never logged")
        assertFalse(guard.wroteSince(cell.copy(x = 1), readNanos = 100L), "and a cell it never wrote is nobody's")
    }
}
