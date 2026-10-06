package com.tracel.plugin.command.presenter.line

import net.kyori.adventure.text.Component
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StackCountTest {
    private fun line(counted: Boolean) = LoggedLine(
        millis = 1L,
        key = "bed",
        mark = Component.empty(),
        who = Component.empty(),
        verb = Component.empty(),
        whoText = "Steve",
        whatText = { "Red Bed" },
        title = "placed",
        what = { Component.empty() },
        counted = counted,
    )

    @Test
    fun `one bed placed is one, whichever half the log shows first`() {
        val headFirst = LineStack(line(counted = false)).also { it.add(line(counted = true)) }
        val footFirst = LineStack(line(counted = true)).also { it.add(line(counted = false)) }
        assertEquals(1, headFirst.count)
        assertEquals(1, footFirst.count)
    }

    @Test
    fun `two beds placed are two`() {
        val stack = LineStack(line(counted = false))
        for (counted in listOf(true, false, true)) stack.add(line(counted))
        assertEquals(2, stack.count)
    }
}
