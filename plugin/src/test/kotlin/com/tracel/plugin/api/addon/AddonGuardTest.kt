package com.tracel.plugin.api.addon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AddonGuardTest {
    private val logger = Logger.getAnonymousLogger()
    private val tripped = AtomicReference<String?>()

    private fun guard(
        hangMillis: Long = HANG_MILLIS,
        stallMillis: Long = STALL_MILLIS,
        inline: Boolean = true,
        excused: Boolean = false,
    ) =
        AddonGuard(
            "test", logger, { tripped.set(it) }, hangMillis, stallMillis, excused,
            lane = if (inline) Dispatchers.Unconfined else Dispatchers.IO.limitedParallelism(1),
        )

    private fun busy(millis: Long) {
        val until = System.nanoTime() + millis * 1_000_000
        @Suppress("ControlFlowWithEmptyBody")
        while (System.nanoTime() < until) {
        }
    }

    @Test
    fun `later calls run one at a time and in order`() {
        val heard = mutableListOf<Int>()
        guard().use { guard -> (1..3).forEach { n -> guard.later("call $n") { heard += n } } }
        assertEquals(listOf(1, 2, 3), heard)
    }

    @Test
    fun `a later call that throws is logged and the next one still runs`() {
        val heard = mutableListOf<Int>()
        guard().use { guard ->
            guard.later("boom") { error("boom") }
            guard.later("after") { heard += 2 }
        }
        assertEquals(listOf(2), heard)
        assertEquals(null, tripped.get())
    }

    @Test
    fun `a hung call disables the addon and nothing of it runs any more`() = runTest {
        guard(hangMillis = 100, inline = false).use { guard ->
            guard.later("hang") { busy(1_500) }
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) { while (tripped.get() == null) delay(10.milliseconds) }
            }
            assertTrue(tripped.get()!!.contains("hang"), "the trip says which call hung")
            assertTrue(guard.isTripped)
            assertThrows<AddonTripped> { guard.now("again") { 1 } }
        }
    }

    @Test
    fun `a call the caller waits on is timed and a stall trips the addon`() {
        guard(stallMillis = 50).use { guard ->
            assertEquals(7, guard.now("quick") { 7 })
            assertEquals(null, tripped.get())
            guard.now("slow") { busy(80) }
            assertTrue(tripped.get()!!.contains("slow"))
        }
    }

    @Test
    fun `a call the caller waits on hands back what it throws`() {
        guard().use { guard ->
            assertThrows<IllegalStateException> { guard.now("boom") { error("boom") } }
            assertEquals(null, tripped.get(), "throwing is the addon's business, not a stall")
        }
    }

    @Test
    fun `an excused slowpoke is named for stalling but keeps running`() {
        guard(stallMillis = 50, excused = true).use { guard ->
            guard.now("slow") { busy(80) }
            assertEquals(null, tripped.get())
            assertEquals(3, guard.now("still here") { 3 })
        }
    }
}
