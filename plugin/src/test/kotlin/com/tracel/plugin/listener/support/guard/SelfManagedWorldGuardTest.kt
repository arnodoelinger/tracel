package com.tracel.plugin.listener.support.guard

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
