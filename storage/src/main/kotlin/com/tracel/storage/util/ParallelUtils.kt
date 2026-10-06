package com.tracel.storage.util

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
        java.util.stream.IntStream.range(0, count).parallel().forEach { body(it) }
    } else {
        for (i in 0 until count) body(i)
    }
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
