package com.tracel.plugin.util.log

import com.tracel.plugin.util.stopping
import java.util.logging.Level
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShutdownTest {
    private val quiet = Logger.getLogger("com.tracel.plugin.ShutdownTest").apply { level = Level.OFF }

    @Test
    fun `a step that throws does not stop the ones after it`() {
        val ran = mutableListOf<String>()

        stopping(
            quiet,
            "the first"
        ) { ran += "first"; throw NoClassDefFoundError("kotlinx/coroutines/JobCancellationException") }
        stopping(quiet, "the second") { ran += "second" }
        stopping(quiet, "storage") { ran += "storage" }

        assertEquals(listOf("first", "second", "storage"), ran, "the store has to be closed whatever happened above it")
    }

    @Test
    fun `an ordinary failure is caught the same way`() {
        var closed = false

        stopping(quiet, "the first") { error("nothing in particular") }
        stopping(quiet, "storage") { closed = true }

        assertEquals(true, closed)
    }

    @Test
    fun `a step that works is simply run`() {
        var ran = 0
        stopping(quiet, "a step") { ran++ }
        assertEquals(1, ran)
    }
}
