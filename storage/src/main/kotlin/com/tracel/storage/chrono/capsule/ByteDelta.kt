package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.SegmentReader

/** A blob written as the edits that turn another blob into it. */
internal object ByteDelta {
    private const val SEED = 4
    private const val MIN_RUN = 5
    private const val MIN_STEP = 3
    private const val PROBES = 8

    /** Appends the ops that turn [ref] into [target] to [out]. */
    fun encode(target: ByteArray, ref: ByteArray, out: ByteWriter) {
        val n = target.size
        val table = Seeds(ref)
        var i = 0
        var literal = 0
        var expected = 0
        while (i < n) {
            var bestLen = 0
            var bestAt = -1
            if (expected < ref.size) {
                val l = run(target, i, ref, expected)
                if (l >= MIN_STEP) {
                    bestLen = l
                    bestAt = expected
                }
            }
            if (bestLen < MIN_RUN && i + SEED <= n) {
                var p = table.first(target, i)
                var probes = PROBES
                while (p >= 0 && probes-- > 0) {
                    val l = run(target, i, ref, p)
                    if (l > bestLen) {
                        bestLen = l
                        bestAt = p
                    }
                    p = table.next(p)
                }
            }
            if (bestAt >= 0 && (bestLen >= MIN_RUN || (bestAt == expected && bestLen >= MIN_STEP))) {
                flush(target, literal, i, out)
                out.varint(bestLen.toLong() shl 1 or 1L)
                out.signed((bestAt - expected).toLong())
                i += bestLen
                literal = i
                expected = bestAt + bestLen
            } else {
                i++
            }
        }
        flush(target, literal, n, out)
    }

    /** The blob [length] bytes long that the ops at [cursor] make from [ref]. */
    fun decode(cursor: SegmentReader, ref: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var at = 0
        var expected = 0
        while (at < length) {
            val op = cursor.varint()
            val n = (op ushr 1).toInt()
            check(n > 0 && at + n <= length) { "a delta op of $n bytes overruns the $length it makes." }
            if (op and 1L == 0L) {
                cursor.bytes(n).copyInto(out, at)
            } else {
                val from = expected + cursor.signed().toInt()
                check(from >= 0 && from + n <= ref.size) { "a delta copies $n bytes from $from of a ${ref.size}-byte reference." }
                ref.copyInto(out, at, from, from + n)
                expected = from + n
            }
            at += n
        }
        return out
    }

    private fun flush(target: ByteArray, from: Int, to: Int, out: ByteWriter) {
        if (to <= from) return
        out.varint((to - from).toLong() shl 1)
        out.bytes(target, from, to - from)
    }

    private fun run(a: ByteArray, ai: Int, b: ByteArray, bi: Int): Int {
        var n = 0
        val max = minOf(a.size - ai, b.size - bi)
        while (n < max && a[ai + n] == b[bi + n]) n++
        return n
    }

    /** Where each four-byte window of a reference starts, newest first along a chain. */
    private class Seeds(private val ref: ByteArray) {
        private val mask = Integer.highestOneBit(maxOf(ref.size, 16)) * 2 - 1
        private val head = IntArray(mask + 1) { -1 }
        private val prev = IntArray(ref.size) { -1 }

        init {
            for (p in 0..ref.size - SEED) {
                val h = hash(ref, p)
                prev[p] = head[h]
                head[h] = p
            }
        }

        fun first(bytes: ByteArray, at: Int): Int = head[hash(bytes, at)]

        fun next(p: Int): Int = prev[p]

        private fun hash(b: ByteArray, at: Int): Int {
            val v = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
                    ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
            return (v * -0x61c88647 ushr 7) and mask
        }
    }
}
