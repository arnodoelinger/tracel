package com.tracel.storage.ports.log

import com.tracel.model.world.*
import com.tracel.storage.util.eachBatch
import com.tracel.storage.util.eachTask
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

// Heavily inspired by the open-addressing hash maps in Agrona
// https://github.com/aeron-io/agrona/blob/master/agrona/src/main/java/org/agrona/collections/Int2IntHashMap.java

internal const val BLOCK_KEY: Byte = 1
internal const val ENTITY_KEY: Byte = 2

private const val PACKED_ENDS_FROM = 1024

/**
 * Finds the first and last occurrence of every key among [n] rows.
 *
 * Caller fills [k1], [k2] and [kind] (in any order, from any threads),
 * then calls [mark]. Rows with kind == 0 are ignored. Two rows belong to the
 * same key when kind + both longs match.
 *
 * How it works:
 * 1. Rows are distributed into independent shards by key hash
 * 2. Each shard builds its own open-addressing table that stores
 *    for every key: (k1, k2, newest index, oldest index)
 * 3. After the tables are ready, the oldest and newest rows of each key
 *    are marked in the `pick` array
 *
 * Shards never share data while building, so the work is fully parallel.
 *
 * Tables stay small and cache-friendly because each shard only sees
 * a fraction of the keys.
 */
internal class EndsIndex(
    private val n: Int,
    private val shards: Int = shardsFor(n),
    private val slotsFor: (expected: Int) -> Int = ::defaultSlots,
) {
    val k1 = LongArray(n)
    val k2 = LongArray(n)
    val kind = ByteArray(n)

    /** Marks both ends of every key in [pick], and says how many rows that newly marked. */
    fun mark(pick: BooleanArray): Int {
        val shardOf = if (shards == 1) null else ByteArray(n)
        val keyed = AtomicIntegerArray(shards)
        eachBatch(n) { from, to ->
            val local = IntArray(shards)
            for (i in from until to) {
                if (kind[i].toInt() == 0) {
                    shardOf?.set(i, -1)
                    continue
                }
                val s = if (shardOf == null) 0 else shardOf(hash(k1[i], k2[i]), shards)
                shardOf?.set(i, s.toByte())
                local[s]++
            }
            for (s in 0 until shards) if (local[s] != 0) keyed.addAndGet(s, local[s])
        }
        val marked = AtomicInteger()
        eachTask(shards) { s -> marked.addAndGet(buildAndMark(s, keyed.get(s), shardOf, pick)) }
        return marked.get()
    }

    private fun buildAndMark(shard: Int, expected: Int, shardOf: ByteArray?, pick: BooleanArray): Int {
        if (expected == 0) return 0
        var slots = slotsFor(expected)
        var table = LongArray(slots * STRIDE)
        var mask = slots - 1
        var used = 0
        for (i in 0 until n) {
            if (shardOf == null) {
                if (kind[i].toInt() == 0) continue
            } else if (shardOf[i].toInt() != shard) continue
            val a = k1[i]
            val b = k2[i]
            val row = i + 1
            var h = slotOf(hash(a, b), mask)
            while (true) {
                val at = h * STRIDE
                val packed = table[at + 2]
                if (packed == 0L) {
                    table[at] = a
                    table[at + 1] = b
                    table[at + 2] = (row.toLong() shl 32) or row.toLong()
                    if (++used * 5 > slots * 4) {
                        table = grown(table, slots)
                        slots *= 2
                        mask = slots - 1
                    }
                    break
                }
                if (table[at] == a && table[at + 1] == b && kind[(packed ushr 32).toInt() - 1] == kind[i]) {
                    val newest = maxOf((packed ushr 32).toInt(), row)
                    val oldest = minOf(packed.toInt(), row)
                    table[at + 2] = (newest.toLong() shl 32) or oldest.toLong()
                    break
                }
                h = (h + 1) and mask
            }
        }
        var count = 0
        for (slot in 0 until slots) {
            val packed = table[slot * STRIDE + 2]
            if (packed == 0L) continue
            val newest = (packed ushr 32).toInt() - 1
            val oldest = packed.toInt() - 1
            if (!pick[newest]) {
                pick[newest] = true
                count++
            }
            if (!pick[oldest]) {
                pick[oldest] = true
                count++
            }
        }
        return count
    }

    private fun grown(old: LongArray, slots: Int): LongArray {
        val bigger = LongArray(old.size * 2)
        val mask = slots * 2 - 1
        for (slot in 0 until slots) {
            val at = slot * STRIDE
            if (old[at + 2] == 0L) continue
            var h = slotOf(hash(old[at], old[at + 1]), mask)
            while (bigger[h * STRIDE + 2] != 0L) h = (h + 1) and mask
            System.arraycopy(old, at, bigger, h * STRIDE, STRIDE)
        }
        return bigger
    }

    companion object {
        private const val STRIDE = 3
        private const val PARALLEL_FROM = 50_000
        private const val MAX_SHARDS = 64

        fun shardsFor(n: Int): Int =
            if (n < PARALLEL_FROM) 1 else minOf(MAX_SHARDS, ForkJoinPool.commonPool().parallelism + 1)

        fun defaultSlots(expected: Int): Int =
            Integer.highestOneBit(((expected.toLong() * 3 / 2).coerceIn(16L, 1L shl 29)).toInt() - 1) shl 1 //

        private fun hash(a: Long, b: Long): Long {
            var h = (a xor (b * -0x4b47d4a8a3b9d5e3L)) * -0x61c8864680b583ebL
            h = (h xor (h ushr 32)) * -0x3d4d51cb7e0a3f6bL
            return h xor (h ushr 29)
        }

        private fun shardOf(hash: Long, shards: Int): Int = (((hash ushr 33) * shards) ushr 31).toInt()

        private fun slotOf(hash: Long, mask: Int): Int = hash.toInt() and mask
    }
}

/** The first and the last change of every block and entity in [ordered], newest first. */
internal fun structureEndsOf(ordered: List<WorldChange>): ArrayList<WorldChange> {
    val n = ordered.size
    packedEndsOf(ordered)?.let { return it }
    // Both ends in one entry. Two maps meant hashing every coordinate twice on the way in and
    // walking two sets of values on the way out, for an answer one map already held.
    val ends = HashMap<Any, IntArray>(n.coerceAtMost(65_536))
    for (i in n - 1 downTo 0) {
        val slot = ends.getOrPut(ordered[i].structureKey()) { intArrayOf(i, i) }
        slot[1] = i
    }
    val pick = BooleanArray(n)
    for (slot in ends.values) {
        pick[slot[0]] = true
        pick[slot[1]] = true
    }
    val out = ArrayList<WorldChange>(ends.size * 2)
    for (i in n - 1 downTo 0) if (pick[i]) out += ordered[i]
    return out
}

private fun packedEndsOf(ordered: List<WorldChange>): ArrayList<WorldChange>? {
    val n = ordered.size
    if (n < PACKED_ENDS_FROM) return null
    val world = ordered[0].at.world
    val ends = EndsIndex(n)
    val foreign = AtomicBoolean()
    eachBatch(n) { from, to ->
        for (i in from until to) {
            val change = ordered[i]
            val subject = change.subject
            if (subject is ChangeSubject.Entity) {
                ends.k1[i] = subject.entity.mostSignificantBits
                ends.k2[i] = subject.entity.leastSignificantBits
                ends.kind[i] = ENTITY_KEY
            } else {
                val at = change.at
                if (at.world != world) {
                    foreign.set(true)
                    return@eachBatch
                }
                ends.k1[i] = (at.x.toLong() shl 32) or (at.z.toLong() and 0xFFFFFFFFL)
                ends.k2[i] = at.y.toLong() shl 32
                ends.kind[i] = BLOCK_KEY
            }
        }
    }
    if (foreign.get()) return null
    val pick = BooleanArray(n)
    val count = ends.mark(pick)
    val out = ArrayList<WorldChange>(count)
    for (i in n - 1 downTo 0) if (pick[i]) out += ordered[i]
    return out
}

private fun WorldChange.structureKey(): Any = when (val subject = subject) {
    is ChangeSubject.Block -> at
    is ChangeSubject.Entity -> subject.entity
}
