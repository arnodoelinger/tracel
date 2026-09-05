package com.tracel.storage.util

/** The candidates as a sorted array. Indexes hand back what they find, not what anyone asked for. */
internal fun ascending(candidates: List<Long>): LongArray {
    val seqs = candidates.toLongArray()
    java.util.Arrays.sort(seqs)
    return seqs
}

/** The newest-first page of an ascending list, without reversing it. */
internal fun pageNewest(seqs: LongArray, offset: Int, limit: Int): LongArray {
    val n = seqs.size
    if (n == 0 || offset >= n) return LongArray(0)
    val end = n - offset
    val start = (end - limit).coerceAtLeast(0)
    return if (start == 0 && end == n) seqs else seqs.copyOfRange(start, end)
}
