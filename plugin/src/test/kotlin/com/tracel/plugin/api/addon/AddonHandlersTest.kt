package com.tracel.plugin.api.addon

import com.tracel.api.addon.Addon
import com.tracel.api.addon.InspectLines
import com.tracel.api.addon.OnInspect
import com.tracel.api.addon.OnRecord
import com.tracel.api.addon.OnRollbackFinished
import com.tracel.api.addon.OnRollbackStarted
import com.tracel.api.addon.OnUndo
import com.tracel.api.addon.RollbackJob
import com.tracel.api.addon.RollbackOutcome
import com.tracel.api.history.CauseKind
import com.tracel.api.history.EventKind
import com.tracel.api.history.EventRecord
import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.RecordKind
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.world.BlockPlace
import java.time.Instant
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AddonHandlersTest {
    private val logged = ArrayList<LogRecord>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                logged += record
            }

            override fun flush() {}

            override fun close() {}
        })
    }
    private val extensions = Extensions(logger)
    private val feeds = ArrayList<Feed>()

    private class Feed(val only: Set<RecordKind>, val sink: (HistoryRecord) -> Unit) : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private fun manager() = AddonManager(
        logger,
        isPluginEnabled = { true },
        extensions = extensions,
        binders = listOf(HandlerBinder(extensions, { only, sink -> Feed(only, sink).also { feeds += it } }, logger)),
    )

    private val place = BlockPlace(UUID(1, 2), 3, 64, 5)
    private val job = RollbackJob(1, null, 12)
    private val done = RollbackOutcome.Done(7, 10, 2, 0, true)
    private val record = EventRecord(
        Instant.EPOCH, null, CauseKind.UNKNOWN, null, null, HistoryCursor(0, 1), EventKind.CHAT, "hi", null,
    )

    @Addon(id = "listens")
    class Listens {
        val heard = ArrayList<String>()

        @OnRecord(only = [RecordKind.EVENT])
        fun record(record: HistoryRecord) {
            heard += "record ${(record as EventRecord).text}"
        }

        @OnRollbackStarted
        fun started() {
            heard += "started"
        }

        @OnRollbackFinished
        fun finished(job: RollbackJob, outcome: RollbackOutcome) {
            heard += "finished ${job.records} $outcome"
        }

        @OnUndo
        private fun undone(undone: RollbackUndone) {
            heard += "undone ${undone.job}"
        }

        @OnInspect
        fun inspect(place: BlockPlace, lines: InspectLines) {
            lines.add("at $place")
        }
    }

    @Test
    fun `handlers taking a leading part of their parameters get exactly that part`() {
        val addon = Listens()
        val manager = manager()
        manager.register(addon)
        manager.start()

        assertEquals(setOf(RecordKind.EVENT), feeds.single().only)
        feeds.single().sink(record)
        for (hearer in extensions.rollbacks.all) {
            hearer.started(job)
            hearer.finished(job, done)
            hearer.undone(RollbackUndone(7, 1, 0, true))
        }
        val lines = extensions.inspect(place)

        assertEquals(listOf("record hi", "started", "finished 12 $done", "undone 7"), addon.heard)
        assertEquals(1, lines.size)
    }

    @Addon(id = "backwards")
    class Backwards {
        @OnRollbackFinished
        fun finished(outcome: RollbackOutcome) = Unit
    }

    @Addon(id = "greedy")
    class Greedy {
        @OnInspect
        fun inspect(place: BlockPlace, lines: InspectLines, extra: String) = Unit
    }

    @Test
    fun `a handler with a wrong signature fails registration and names what it should take`() {
        val manager = manager()
        val wrong = assertThrows<IllegalArgumentException> { manager.register(Backwards()) }
        val message = wrong.message.orEmpty()
        assertTrue("Backwards.finished" in message, message)
        assertTrue("(RollbackJob, RollbackOutcome)" in message, message)

        assertThrows<IllegalArgumentException> { manager.register(Greedy()) }
        manager.start()
        assertTrue(manager.enabledIds.isEmpty())
    }

    @Addon(id = "throws")
    class Throws {
        @OnRollbackStarted
        fun started(job: RollbackJob): Unit = error("boom")

        @OnInspect
        fun inspect(): Unit = error("boom")
    }

    @Test
    fun `a handler that throws is logged and everyone else still hears`() {
        val addon = Listens()
        val manager = manager()
        manager.register(Throws())
        manager.register(addon)
        manager.start()

        extensions.rollbacks.all.forEach { it.started(job) }
        val lines = extensions.inspect(place)

        assertEquals(listOf("started"), addon.heard)
        assertEquals(1, lines.size)
        assertTrue(logged.any { "Throws.started" in it.message })
        assertTrue(logged.any { "Throws.inspect" in it.message })
    }

    @Test
    fun `everything a handler subscribed to ends when its addon is disabled`() {
        val manager = manager()
        manager.register(Listens())
        manager.start()
        manager.stop()

        assertTrue(feeds.single().closed)
        assertTrue(extensions.rollbacks.all.isEmpty())
        assertTrue(extensions.inspect(place).isEmpty())

        manager.start()
        assertEquals(2, feeds.size)
        assertEquals(1, extensions.rollbacks.all.size)
    }
}
