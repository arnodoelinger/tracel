package com.tracel.storage.util

import it.unimi.dsi.fastutil.longs.LongArrayList

private const val MIN_PAGE_SLICE = 256
private const val MAX_PAGE_SLICE = 1 shl 20

/** The candidates as a sorted array. Indexes hand back what they find, not what anyone asked for. */
internal fun ascending(candidates: LongArrayList): LongArray {
    val seqs = candidates.toLongArray()
    java.util.Arrays.sort(seqs)
    var kept = 0
    for (i in seqs.indices) if (kept == 0 || seqs[i] != seqs[kept - 1]) seqs[kept++] = seqs[i]
    return if (kept == seqs.size) seqs else seqs.copyOf(kept)
}

/** Candidates [pageAccepted] kept, ascending, with the record each one was accepted on. */
internal class Accepted(capacity: Int = 16) {
    val seqs = LongArrayList(capacity)
    val records = ArrayList<java.lang.foreign.MemorySegment>(capacity)
}

/** [parts] back to back, in order. They have to be disjoint and ascending already. */
internal fun concatenated(parts: Array<Accepted?>): Accepted {
    var n = 0
    for (part in parts) n += part!!.seqs.size
    val out = Accepted(n)
    for (part in parts) {
        out.seqs.addAll(part!!.seqs)
        out.records += part.records
    }
    return out
}

/** Sorted runs merged into one ascending run, each sequence once. */
internal fun mergedAscending(runs: Array<Accepted?>): Accepted {
    val ordered = runs.filter { it!!.seqs.size > 0 }.sortedBy { it!!.seqs.getLong(0) }.toTypedArray()
    var disjoint = true
    for (i in 1 until ordered.size) {
        val previous = ordered[i - 1]!!.seqs
        if (ordered[i]!!.seqs.getLong(0) <= previous.getLong(previous.size - 1)) disjoint = false
    }
    if (disjoint) return concatenated(ordered)

    var n = 0
    for (run in ordered) n += run!!.seqs.size
    val out = Accepted(n)
    val at = IntArray(ordered.size)
    val heads = LongArray(ordered.size) { -ordered[it]!!.seqs.getLong(0) }
    val heap = IntArray(ordered.size)
    for (i in ordered.indices) {
        heap[i] = i
        siftUp(heap, heads, i)
    }
    var size = ordered.size
    var previous = Long.MIN_VALUE
    while (size > 0) {
        val best = heap[0]
        val run = ordered[best]!!
        val seq = run.seqs.getLong(at[best])
        if (out.seqs.isEmpty || seq != previous) {
            out.seqs.add(seq)
            out.records += run.records[at[best]]
            previous = seq
        }
        if (++at[best] < run.seqs.size) {
            heads[best] = -run.seqs.getLong(at[best])
        } else {
            heap[0] = heap[--size]
        }
        siftDown(heap, heads, size)
    }
    return out
}

/** The newest-first page of the candidates the record-level filter keeps. */
internal fun pageAccepted(
    seqs: LongArray,
    offset: Int,
    limit: Int,
    fetch: (LongArray, (Long, java.lang.foreign.MemorySegment) -> Unit) -> Unit,
): Accepted {
    val want = offset.toLong() + limit
    val slices = ArrayList<Accepted>()
    var end = seqs.size
    var chunk =
        if (want >= seqs.size) seqs.size else maxOf(want * 2, MIN_PAGE_SLICE.toLong()).coerceAtMost(seqs.size.toLong())
            .toInt()
    var total = 0L
    while (end > 0 && total < want) {
        val start = (end - chunk).coerceAtLeast(0)
        val part = Accepted()
        fetch(if (start == 0 && end == seqs.size) seqs else seqs.copyOfRange(start, end)) { seq, record ->
            part.seqs.add(seq)
            part.records += record
        }
        slices += part
        total += part.seqs.size
        end = start
        chunk = (chunk.toLong() * 2).coerceAtMost(MAX_PAGE_SLICE.toLong()).toInt()
    }
    val all = Accepted(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    for (part in slices.asReversed()) {
        all.seqs.addAll(part.seqs)
        all.records += part.records
    }
    val n = all.seqs.size
    val to = (n - offset).coerceAtLeast(0)
    val from = (to - limit).coerceAtLeast(0)
    if (from == 0 && to == n) return all
    return Accepted(to - from).also {
        it.seqs.addAll(all.seqs.subList(from, to))
        it.records += all.records.subList(from, to)
    }
}
