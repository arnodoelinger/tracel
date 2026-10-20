package com.tracel.plugin.rollback.outside

import com.tracel.engine.log.RolledBack
import com.tracel.engine.rollback.outside.OutsideStep
import com.tracel.engine.rollback.outside.OutsideSteps
import com.tracel.plugin.api.addon.AddonGuard
import com.tracel.plugin.api.addon.BoundReversible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Logger

class OutsideReconcilerTest {
    private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

    private class Marks(val taken: MutableSet<Long> = HashSet()) : RolledBack {
        override suspend fun mark(job: Long, seqs: LongArray, millis: Long) {
            taken += seqs.toList()
        }

        override suspend fun restore(job: Long) = taken.clear()

        override suspend fun of(seqs: Collection<Long>): Map<Long, Long> =
            seqs.filter { it in taken }.associateWith { 1L }
    }

    private class Kept : OutsideSteps {
        val rows = LinkedHashMap<Long, OutsideStep>()

        override suspend fun stage(steps: List<OutsideStep>) = steps.forEach { rows.putIfAbsent(it.seq, it) }

        override suspend fun bind(job: Long, seqs: LongArray) =
            seqs.forEach { seq -> rows[seq]?.let { rows[seq] = it.copy(job = job) } }

        override suspend fun ofJob(job: Long) = rows.values.filter { it.job == job }

        override suspend fun ofKind(kind: String) = rows.values.filter { it.kind == kind }

        override suspend fun told(seq: Long, undone: Boolean, generation: Int) {
            val kept = rows[seq] ?: return
            if (generation > kept.generation) rows[seq] = kept.copy(undone = undone, generation = generation)
        }
    }

    private val marks = Marks()
    private val kept = Kept()
    private val heard = mutableListOf<String>()
    private var failing = false
    private var present = true

    private val bound = BoundReversible(AddonGuard("eco", logger, {}, lane = Dispatchers.Unconfined)) { undone, step, text ->
        if (failing) error("bank is down")
        heard += "${if (undone) "undo" else "redo"} ${step.id} $text"
        true
    }

    private fun reconciler(scope: CoroutineScope) =
        OutsideReconciler(marks, kept, scope) { if (present) bound else null }

    private val paid = OutsideStep(10, "eco:paid", "@1! alice bob 100", job = 7)

    @Test
    fun `a rollback takes it back once, its undo does it again once`() = runTest {
        kept.stage(listOf(paid))
        val reconciler = reconciler(this)

        marks.taken += 10
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()

        marks.taken.clear()
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("undo 10:1 @1! alice bob 100", "redo 10:2 @1! alice bob 100"), heard)
        assertEquals(paid.copy(undone = false, generation = 2), kept.rows[10])
    }

    @Test
    fun `a call that failed is made again with the same id`() = runTest {
        kept.stage(listOf(paid))
        val reconciler = reconciler(this)
        marks.taken += 10

        failing = true
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()
        failing = false
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("undo 10:1 @1! alice bob 100"), heard, "same id, so the addon can tell it is a repeat")
    }

    @Test
    fun `an addon that is off is told once it is back`() = runTest {
        kept.stage(listOf(paid))
        val reconciler = reconciler(this)
        marks.taken += 10

        present = false
        reconciler.reconcile(kept.ofJob(7))
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList<String>(), heard)

        present = true
        reconciler.reconcileKind("eco:paid")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("undo 10:1 @1! alice bob 100"), heard)
    }
}
