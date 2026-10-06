package com.tracel.engine.rollback.journal.support

import com.tracel.engine.rollback.plan.step.RollbackStep

/**
 * A lazy view of the unfinished steps in a range.
 *
 * Avoids allocating a new list when a batch contains only a few unfinished steps.
 */
internal class SparseSteps(
    private val steps: List<RollbackStep>,
    private val words: LongArray,
    private val from: Int,
    private val until: Int,
    override val size: Int,
) : List<RollbackStep> {
    override fun isEmpty(): Boolean = size == 0

    override fun contains(element: RollbackStep): Boolean {
        for (step in this) if (step == element) return true
        return false
    }

    override fun containsAll(elements: Collection<RollbackStep>): Boolean {
        for (element in elements) if (!contains(element)) return false
        return true
    }

    override fun get(index: Int): RollbackStep {
        if (index !in indices) throw IndexOutOfBoundsException(index)
        var seen = 0
        var i = from
        while (i < until) {
            if (words[i ushr 6] and (1L shl (i and 63)) == 0L) {
                if (seen == index) return steps[i]
                seen++
            }
            i++
        }
        throw IndexOutOfBoundsException(index)
    }

    override fun indexOf(element: RollbackStep): Int {
        for ((seen, step) in this.withIndex()) {
            if (step == element) return seen
        }
        return -1
    }

    override fun lastIndexOf(element: RollbackStep): Int {
        var last = -1
        for ((seen, step) in this.withIndex()) {
            if (step == element) last = seen
        }
        return last
    }

    override fun iterator(): Iterator<RollbackStep> = object : Iterator<RollbackStep> {
        private var i = from

        init {
            skipDone()
        }

        private fun skipDone() {
            while (i < until && words[i ushr 6] and (1L shl (i and 63)) != 0L) i++
        }

        override fun hasNext(): Boolean = i < until

        override fun next(): RollbackStep {
            if (!hasNext()) throw NoSuchElementException()
            val step = steps[i++]
            skipDone()
            return step
        }
    }

    override fun listIterator(): ListIterator<RollbackStep> = snapshot().listIterator()

    override fun listIterator(index: Int): ListIterator<RollbackStep> = snapshot().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<RollbackStep> = snapshot().subList(fromIndex, toIndex)

    private fun snapshot(): ArrayList<RollbackStep> {
        val out = ArrayList<RollbackStep>(size)
        for (step in this) out.add(step)
        return out
    }
}
