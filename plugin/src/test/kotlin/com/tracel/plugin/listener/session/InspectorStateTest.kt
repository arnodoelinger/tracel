package com.tracel.plugin.listener.session

import java.util.*
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorStateTest {
    @Test
    fun `toggle flips on then off, and isActive tracks it`() {
        val state = InspectorState()
        val player = UUID.randomUUID()

        assertFalse(state.isActive(player))
        assertTrue(state.toggle(player))
        assertTrue(state.isActive(player))
        assertFalse(state.toggle(player))
        assertFalse(state.isActive(player))
    }

    @Test
    fun `players don't share state`() {
        val state = InspectorState()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()

        state.toggle(a)

        assertTrue(state.isActive(a))
        assertFalse(state.isActive(b))
    }

    @Test
    fun `clear turns inspect mode off regardless of current state`() {
        val state = InspectorState()
        val player = UUID.randomUUID()

        state.clear(player)
        assertFalse(state.isActive(player))

        state.toggle(player)
        state.clear(player)
        assertFalse(state.isActive(player))
    }
}
