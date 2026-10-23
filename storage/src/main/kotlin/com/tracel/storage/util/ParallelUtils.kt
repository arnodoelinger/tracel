package com.tracel.storage.util

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntConsumer

private const val MIN_BATCH = 256

/**
 * Below this a batch is not worth splitting.
 *
 * Sixty-four independent gets is roughly where the common pool's hand-off stops dominating. Well
 * under it lives every ordinary lookup, which stays on one thread and pays nothing.
 */
internal const val PARALLEL_GETS_FROM = 64

/** Runs [body] for each index below [count] — in parallel when there is enough work to justify it. */
internal inline fun eachIndex(count: Int, work: Int = count, crossinline body: (Int) -> Unit) {
    if (work >= PARALLEL_GETS_FROM) {
        eachBatch(count) { from, to -> for (i in from until to) body(i) }
    } else {
        for (i in 0 until count) body(i)
    }
}

/** A run of indices, `from` up to but not including `to`. */
internal fun interface IndexSlice {
    fun run(from: Int, to: Int)
}

/**
 * Hands the indices below [count] out in batches, to the caller and to the common pool's
 * workers alike, until none are left.
 *
 * A parallel stream splits the range into spliterators and tasks all the way down.
 * Here a worker takes the next batch from one counter, so it costs a handful of tasks
 * however long the range, and a slow batch holds up nobody else.
 */
internal fun eachBatch(count: Int, slice: IndexSlice) = eachBatch(count, 0, slice)

/** [eachBatch] where every index is a task of its own, for a few big pieces of work rather than many small ones. */
internal fun eachTask(count: Int, body: IntConsumer) = eachBatch(count, 1) { from, to ->
    for (i in from until to) body.accept(i)
}

/** Loads [seqs] (ascending) newest-first, dropping whatever [load] cannot find. */
@Suppress("UNCHECKED_CAST")
internal inline fun <T : Any> collectNewestFirst(
    seqs: LongArray,
    crossinline load: (Long) -> T?,
): ArrayList<T> {
    val n = seqs.size
    if (n == 0) return ArrayList()
    val slots = arrayOfNulls<Any>(n)
    eachIndex(n) { i -> slots[i] = load(seqs[i]) }
    val out = ArrayList<T>(n)
    for (i in n - 1 downTo 0) {
        val hit = slots[i] as T?
        if (hit != null) out += hit
    }
    return out
}

private fun eachBatch(count: Int, fixedBatch: Int, slice: IndexSlice) {
    if (count <= 0) return
    val pool = ForkJoinPool.commonPool()
    val batch = if (fixedBatch > 0) fixedBatch else maxOf(MIN_BATCH, count / (pool.parallelism * 64))
    val helpers = minOf(pool.parallelism, (count + batch - 1) / batch - 1)
    val next = AtomicInteger()
    val worker = Runnable {
        while (true) {
            val from = next.getAndAdd(batch)
            if (from >= count) break
            slice.run(from, minOf(count, from + batch))
        }
    }
    if (helpers <= 0) {
        worker.run()
        return
    }
    val tasks = arrayOfNulls<ForkJoinTask<*>>(helpers)
    for (i in 0 until helpers) tasks[i] = pool.submit(worker)
    var failure: Throwable? = null
    try {
        worker.run()
    } catch (e: Throwable) {
        failure = e
        next.set(count)
    }
    for (task in tasks) {
        try {
            task!!.join()
        } catch (e: Throwable) {
            if (failure == null) failure = e else failure.addSuppressed(e)
        }
    }
    failure?.let { throw it }
}
