package com.tracel.storage.lsm.read

import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
import com.tracel.storage.spi.EngineCursor
import java.lang.foreign.MemorySegment

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

/** From less. */
internal fun less(left: Run, right: Run): Boolean = SegmentCompare.compare(
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
    initial: Array<Run>,
    private val prefix: ByteArray,
    private val snapshotSequence: Long,
    private val onClose: () -> Unit,
) : EngineCursor {
    private var runs: Array<Run> = initial
    private var scratch = ByteArray(64)
    private var scratchLength = -1
    private var currentValue: MemorySegment? = null

    private var count = runs.size
    private var r0: Run = NoRun
    private var r1: Run = NoRun
    private var r2: Run = NoRun
    private var r3: Run = NoRun

    init {
        prune()
    }

    @Suppress("UNCHECKED_CAST")
    private fun prune() {
        var kept = 0
        for (run in runs) {
            if (!run.valid) continue
            if (!SegmentCompare.startsWith(run.keySegment, run.keyOffset, run.userKeyLength, prefix)) continue
            runs[kept++] = run
        }
        if (kept != runs.size) runs = runs.copyOf(kept) as Array<Run>
        count = kept
        r0 = if (count > 0) runs[0] else NoRun
        r1 = if (count > 1) runs[1] else NoRun
        r2 = if (count > 2) runs[2] else NoRun
        r3 = if (count > 3) runs[3] else NoRun
    }

    override fun next(): Boolean {
        while (true) {
            val first = minimum()
            if (first < 0) return finish()
            val head = runs[first]
            if (!SegmentCompare.startsWith(head.keySegment, head.keyOffset, head.userKeyLength, prefix)) {
                return finish()
            }
            val userKeyLength = head.userKeyLength
            if (scratch.size < userKeyLength) scratch = ByteArray(userKeyLength + 32)
            val userKey = scratch
            head.userKeyInto(userKey)

            var found = false
            var deletion = false
            var value: MemorySegment? = null

            for (index in runs.indices) {
                val run = runs[index]
                while (run.valid && sameUserKey(run, userKey, userKeyLength)) {
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
                if (run.valid && sameUserKey(run, userKey, userKeyLength)) {
                    run.next()
                    if (run.valid && sameUserKey(run, userKey, userKeyLength)) {
                        run.skipPast(userKey, userKeyLength)
                    }
                }
            }

            if (found && !deletion) {
                scratchLength = userKeyLength
                currentValue = value
                return true
            }
        }
    }

    override fun key(): ByteArray {
        if (scratchLength < 0) error("cursor is not positioned")
        return scratch.copyOf(scratchLength)
    }

    override fun keyLength(): Int {
        if (scratchLength < 0) error("cursor is not positioned")
        return scratchLength
    }

    override fun keyByte(at: Int): Byte {
        if (scratchLength < 0) error("cursor is not positioned")
        return scratch[at]
    }

    override fun keyU32(at: Int): Int {
        if (scratchLength < 0) error("cursor is not positioned")
        val key = scratch
        return ((key[at].toInt() and 0xFF) shl 24) or ((key[at + 1].toInt() and 0xFF) shl 16) or
                ((key[at + 2].toInt() and 0xFF) shl 8) or (key[at + 3].toInt() and 0xFF)
    }

    override fun keyU64(at: Int): Long {
        if (scratchLength < 0) error("cursor is not positioned")
        val key = scratch
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (key[at + i].toLong() and 0xFF)
        return value
    }

    override fun value(): MemorySegment = currentValue ?: error("cursor is not positioned")

    override fun skipTo(from: ByteArray) {
        val target = InternalKey.seekTarget(from)
        for (run in runs) run.seek(target, target.size)
        prune()
        scratchLength = -1
        currentValue = null
    }

    override fun close() {
        onClose()
    }

    private fun finish(): Boolean {
        scratchLength = -1
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
                if (r1.valid && (best < 0 || less(r1, winner))) {
                    best = 1; winner = r1
                }
                if (r2.valid && (best < 0 || less(r2, winner))) best = 2
                return best
            }

            4 -> {
                var best = -1
                var winner = r0
                if (r0.valid) best = 0
                if (r1.valid && (best < 0 || less(r1, winner))) {
                    best = 1; winner = r1
                }
                if (r2.valid && (best < 0 || less(r2, winner))) {
                    best = 2; winner = r2
                }
                if (r3.valid && (best < 0 || less(r3, winner))) best = 3
                return best
            }
        }
        return minimumOf(runs)
    }

    private fun sameUserKey(run: Run, userKey: ByteArray, length: Int): Boolean =
        run.userKeyLength == length &&
                SegmentCompare.compare(run.keySegment, run.keyOffset, length, userKey, length) == 0

    private companion object {
        @Suppress("RedundantNullableReturnType")
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
