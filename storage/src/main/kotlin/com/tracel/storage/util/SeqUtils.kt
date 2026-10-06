package com.tracel.storage.util

private const val MIN_PAGE_SLICE = 256
private const val MAX_PAGE_SLICE = 1 shl 20

/** The candidates as a sorted array. Indexes hand back what they find, not what anyone asked for. */
internal fun ascending(candidates: List<Long>): LongArray {
    val seqs = candidates.toLongArray()
    java.util.Arrays.sort(seqs)
    var kept = 0
    for (i in seqs.indices) if (kept == 0 || seqs[i] != seqs[kept - 1]) seqs[kept++] = seqs[i]
    return if (kept == seqs.size) seqs else seqs.copyOf(kept)
}

/** Candidates [pageAccepted] kept, ascending, with the record each one was accepted on. */
internal class Accepted(capacity: Int = 16) {
    val seqs = ArrayList<Long>(capacity)
    val records = ArrayList<java.lang.foreign.MemorySegment>(capacity)
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
            part.seqs += seq
            part.records += record
        }
        slices += part
        total += part.seqs.size
        end = start
        chunk = (chunk.toLong() * 2).coerceAtMost(MAX_PAGE_SLICE.toLong()).toInt()
    }
    val all = Accepted(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    for (part in slices.asReversed()) {
        all.seqs += part.seqs
        all.records += part.records
    }
    val n = all.seqs.size
    val to = (n - offset).coerceAtLeast(0)
    val from = (to - limit).coerceAtLeast(0)
    if (from == 0 && to == n) return all
    return Accepted(to - from).also {
        it.seqs += all.seqs.subList(from, to)
        it.records += all.records.subList(from, to)
    }
}
