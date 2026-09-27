package com.tracel.storage.util

/** Moves the entry at [at] up until its parent outranks it. */
internal fun siftUp(heap: IntArray, keys: LongArray, at: Int) {
    var i = at
    while (i > 0) {
        val parent = (i - 1) / 2
        if (keys[heap[i]] <= keys[heap[parent]]) break
        val swap = heap[i]
        heap[i] = heap[parent]
        heap[parent] = swap
        i = parent
    }
}

/** Moves the root down until both children are outranked by it. */
internal fun siftDown(heap: IntArray, keys: LongArray, size: Int) {
    var i = 0
    while (true) {
        val left = i * 2 + 1
        if (left >= size) break
        var largest = if (keys[heap[left]] > keys[heap[i]]) left else i
        val right = left + 1
        if (right < size && keys[heap[right]] > keys[heap[largest]]) largest = right
        if (largest == i) break
        val swap = heap[i]
        heap[i] = heap[largest]
        heap[largest] = swap
        i = largest
    }
}
