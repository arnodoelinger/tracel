package com.tracel.storage.lsm

import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.spi.EngineCursor
import java.lang.foreign.MemorySegment
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * One sorted run positioned somewhere in itself. A memtable or a segment file, seen through
 * the same three questions the merge asks: where are you, how new are you, what do you hold.
 */
internal abstract class Run {
    @JvmField var valid: Boolean = false
    @JvmField var keySegment: MemorySegment = MemorySegment.NULL
    @JvmField var keyOffset: Long = 0
    @JvmField var keyLength: Int = 0
    @JvmField var userKeyLength: Int = 0

    abstract fun seek(internalKey: ByteArray, length: Int)
    abstract fun next()
    abstract fun sequence(): Long
    abstract fun isDeletion(): Boolean
    abstract fun value(): MemorySegment?
    abstract fun userKeyBytes(): ByteArray

    fun seek(internalKey: ByteArray) = seek(internalKey, internalKey.size)

    private var past = ByteArray(48)

    /** Jumps past every remaining version of [userKey]. */
    fun skipPast(userKey: ByteArray) {
        val length = userKey.size + InternalKey.TRAILER_BYTES
        var buffer = past
        if (buffer.size < length) {
            buffer = ByteArray(length + 32)
            past = buffer
        }
        System.arraycopy(userKey, 0, buffer, 0, userKey.size)
        BE_LONG.set(buffer, userKey.size, -1L)
        seek(buffer, length)
    }

    companion object {
        private val BE_LONG: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)
    }
}

internal class MemTableRun(private val table: MemTable) : Run() {
    private var node = 0L

    override fun seek(internalKey: ByteArray, length: Int) {
        node = table.seek(internalKey, length)
        refresh()
    }

    override fun next() {
        node = table.nextOf(node, 0)
        refresh()
    }

    override fun sequence(): Long = table.sequenceOf(node)
    override fun isDeletion(): Boolean = table.isDeletion(node)
    override fun value(): MemorySegment? = table.valueOf(node)
    override fun userKeyBytes(): ByteArray = table.userKeyBytes(node)

    private fun refresh() {
        val at = node
        if (at == 0L) {
            valid = false
            return
        }
        val header = table.header(at)
        valid = true
        keySegment = table.segment
        keyOffset = table.keyOffsetOf(at, header)
        keyLength = table.keyLengthOf(header)
        userKeyLength = keyLength - InternalKey.TRAILER_BYTES
    }
}

internal class SegmentRun(private val reader: SegmentReader) : Run() {
    private val end = reader.end()
    private var at = 0L
    private var header = 0L

    override fun seek(internalKey: ByteArray, length: Int) {
        at = reader.seek(internalKey, length)
        refresh()
    }

    override fun next() {
        at = reader.advance(at, header)
        refresh()
    }

    override fun sequence(): Long = reader.sequenceOf(at)
    override fun isDeletion(): Boolean = reader.isDeletionOf(header)
    override fun value(): MemorySegment? = reader.valueOf(at, header)
    override fun userKeyBytes(): ByteArray = reader.userKeyBytes(at)

    private fun refresh() {
        val cursor = at
        if (cursor >= end) {
            valid = false
            return
        }
        val head = reader.header(cursor)
        header = head
        valid = true
        keySegment = reader.segment
        keyOffset = cursor + 8
        keyLength = head.toInt()
        userKeyLength = keyLength - InternalKey.TRAILER_BYTES
    }
}

/** The run positioned at the smallest key, or `-1` when every run is spent. */
internal fun minimumOf(runs: Array<Run>): Int {
    if (runs.isEmpty()) return -1
    var best = -1
    var winner: Run = runs[0]
    for (i in runs.indices) {
        val candidate = runs[i]
        if (!candidate.valid) continue
        if (best < 0 || less(candidate, winner)) {
            best = i
            winner = candidate
        }
    }
    return best
}

private fun less(left: Run, right: Run): Boolean = SegmentCompare.compare(
    left.keySegment, left.keyOffset, left.keyLength,
    right.keySegment, right.keyOffset, right.keyLength,
) < 0

/**
 * Merges every run into one ascending stream of user keys, resolving each key to the newest
 * version at or below [snapshotSequence] and swallowing tombstones.
 *
 * Because internal keys sort `(user key ascending, sequence descending)`, the smallest key
 * across the runs is always the newest version of the smallest user key — so resolution is
 * "take the first version you meet that the snapshot can see, then skip the rest of that key"
 * rather than anything that has to look ahead.
 */
internal class MergingCursor(
    private val runs: Array<Run>,
    private val prefix: ByteArray,
    private val snapshotSequence: Long,
    private val onClose: () -> Unit,
) : EngineCursor {
    private var currentKey: ByteArray? = null
    private var currentValue: MemorySegment? = null

    private val count = runs.size
    private val r0: Run = if (count > 0) runs[0] else NoRun
    private val r1: Run = if (count > 1) runs[1] else NoRun
    private val r2: Run = if (count > 2) runs[2] else NoRun
    private val r3: Run = if (count > 3) runs[3] else NoRun

    override fun next(): Boolean {
        while (true) {
            val first = minimum()
            if (first < 0) return finish()
            val head = runs[first]
            if (!SegmentCompare.startsWith(head.keySegment, head.keyOffset, head.userKeyLength, prefix)) {
                return finish()
            }
            val userKey = head.userKeyBytes()

            var found = false
            var deletion = false
            var value: MemorySegment? = null

            for (index in runs.indices) {
                val run = runs[index]
                while (run.valid && sameUserKey(run, userKey)) {
                    if (!found && run.sequence() <= snapshotSequence) {
                        found = true
                        deletion = run.isDeletion()
                        value = run.value()
                        break
                    }
                    if (run.sequence() <= snapshotSequence) break
                    run.next()
                }

                // Do not delete this next()-then-skipPast() pair and replace it e.g. with a plain
                // skipPast() call, no matter how redundant it looks sitting right under a loop
                // that was just calling next() itself.
                //
                // Before this existed (in my first attempts this shit), I've called run.next()
                // in a plain loop until the user key changed, full stop. No skipPast at all.
                //
                // And that is correct!
                //
                // It is also O(versions-of-this-crap-key) per key, and most keys in this store have
                // exactly one version. Except the ones that don't: a chest a hopper has been
                // feeding all evening, a running total column rewritten on every single
                // captured event. One of those can accumulate thousands of versions in a
                // memtable before the next flush collapses them. A scan that walks every one of
                // those versions one next() at a time, for every hot key it passes over, is not
                // walking the result set anymore. It is walking the write history.
                //
                // This is exactly what happened. The full capture-to-lookup pipeline benchmark
                // was stuck at roughly 9.000 silly events per second no matter how the batch size
                // was even tuned, while the isolated storage engine benchmark — same code path, minus
                // the capture ring and the drain loop around it — showed ~2.4 million entries
                // per second. Nothing in the engine itself was slow. The gap was entirely spent
                // in this stupid loop, walking versions of the running-total keys that the
                // benchmark's own workload kept rewriting.
                //
                // seek() lands anywhere in the log(n) sparse index in one jump. skipPast()
                // builds the exclusive upper bound for a user key (the key followed by an
                // all-0xFF trailer, which sorts after every possible sequence number for it) and
                // seeks straight there — one jump past every remaining version, however many
                // there are.
                //
                // The cheap next() first is not an accident either: for the common
                // single-version key, one next() already lands past it, and skipping straight to
                // a binary search would be strictly more expensive for the case that dominates
                // the workload. Only a key with more than one surviving version pays for the
                // seek.
                //
                // Fixing this took the full pipeline from roughly 9.500 events per second
                // to over 53.000 in group-commit mode (August 23, 2026) — an order of magnitude,
                // from one loop.
                if (run.valid && sameUserKey(run, userKey)) {
                    run.next()
                    if (run.valid && sameUserKey(run, userKey)) run.skipPast(userKey)
                }
            }

            if (found && !deletion) {
                currentKey = userKey
                currentValue = value
                return true
            }
        }
    }

    override fun key(): ByteArray = currentKey ?: error("cursor is not positioned")

    override fun value(): MemorySegment = currentValue ?: error("cursor is not positioned")

    override fun skipTo(from: ByteArray) {
        val target = InternalKey.seekTarget(from)
        for (run in runs) run.seek(target, target.size)
        currentKey = null
        currentValue = null
    }

    override fun close() {
        onClose()
    }

    private fun finish(): Boolean {
        currentKey = null
        currentValue = null
        return false
    }

    private fun minimum(): Int {
        when (count) {
            0 -> return -1
            1 -> return if (r0.valid) 0 else -1
            2 -> {
                if (!r0.valid) return if (r1.valid) 1 else -1
                if (!r1.valid) return 0
                return if (less(r1, r0)) 1 else 0
            }
            3 -> {
                var best = -1
                var winner = r0
                if (r0.valid) best = 0
                if (r1.valid && (best < 0 || less(r1, winner))) { best = 1; winner = r1 }
                if (r2.valid && (best < 0 || less(r2, winner))) best = 2
                return best
            }
            4 -> {
                var best = -1
                var winner = r0
                if (r0.valid) best = 0
                if (r1.valid && (best < 0 || less(r1, winner))) { best = 1; winner = r1 }
                if (r2.valid && (best < 0 || less(r2, winner))) { best = 2; winner = r2 }
                if (r3.valid && (best < 0 || less(r3, winner))) best = 3
                return best
            }
        }
        return minimumOf(runs)
    }

    private fun sameUserKey(run: Run, userKey: ByteArray): Boolean =
        run.userKeyLength == userKey.size &&
            SegmentCompare.compare(run.keySegment, run.keyOffset, userKey.size, userKey, userKey.size) == 0

    private companion object {
        val NoRun = object : Run() {
            override fun seek(internalKey: ByteArray, length: Int) = Unit
            override fun next() = Unit
            override fun sequence(): Long = error("no run")
            override fun isDeletion(): Boolean = error("no run")
            override fun value(): MemorySegment? = error("no run")
            override fun userKeyBytes(): ByteArray = error("no run")
        }
    }
}
