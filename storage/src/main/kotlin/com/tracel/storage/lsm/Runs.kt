package com.tracel.storage.lsm

import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.spi.EngineCursor
import java.lang.foreign.MemorySegment

/**
 * One sorted run positioned somewhere in itself. A memtable or a segment file, seen through
 * the same three questions the merge asks: where are you, how new are you, what do you hold.
 */
internal interface Run {
    fun valid(): Boolean
    fun seek(internalKey: ByteArray)
    fun next()

    fun skipPast(userKey: ByteArray) {
        val past = ByteArray(userKey.size + InternalKey.TRAILER_BYTES)
        userKey.copyInto(past)
        for (i in userKey.size until past.size) past[i] = 0xFF.toByte()
        seek(past)
    }

    fun keySegment(): MemorySegment
    fun keyOffset(): Long
    fun keyLength(): Int
    fun userKeyLength(): Int
    fun sequence(): Long
    fun isDeletion(): Boolean
    fun value(): MemorySegment?
    fun userKeyBytes(): ByteArray
}

internal class MemTableRun(private val table: MemTable) : Run {
    private var node = 0L

    override fun valid(): Boolean = node != 0L
    override fun seek(internalKey: ByteArray) {
        node = table.seek(internalKey)
    }

    override fun next() {
        node = table.nextOf(node, 0)
    }

    override fun keySegment(): MemorySegment = table.segment
    override fun keyOffset(): Long = table.keyOffset(node)
    override fun keyLength(): Int = table.keyLength(node)
    override fun userKeyLength(): Int = table.userKeyLength(node)
    override fun sequence(): Long = table.sequenceOf(node)
    override fun isDeletion(): Boolean = table.isDeletion(node)
    override fun value(): MemorySegment? = table.valueOf(node)
    override fun userKeyBytes(): ByteArray = table.userKeyBytes(node)
}

internal class SegmentRun(private val reader: SegmentReader) : Run {
    private var at = 0L
    private var end = reader.end()

    override fun valid(): Boolean = at < end
    override fun seek(internalKey: ByteArray) {
        at = reader.seek(internalKey)
    }

    override fun next() {
        at = reader.advance(at)
    }

    override fun keySegment(): MemorySegment = reader.segment
    override fun keyOffset(): Long = reader.keyOffset(at)
    override fun keyLength(): Int = reader.keyLength(at)
    override fun userKeyLength(): Int = reader.userKeyLength(at)
    override fun sequence(): Long = reader.sequenceOf(at)
    override fun isDeletion(): Boolean = reader.isDeletion(at)
    override fun value(): MemorySegment? = reader.valueOf(at)
    override fun userKeyBytes(): ByteArray = reader.userKeyBytes(at)
}

/**
 * Merges every run into one ascending stream of user keys, resolving each key to the newest
 * version at or below [snapshotSequence] and swallowing tombstones.
 *
 * Because internal keys sort `(user key ascending, sequence descending)`, the smallest key
 * across the runs is always the newest version of the smallest user key — so resolution is
 * "take the first version you meet that the snapshot can see, then skip the rest of that key"
 * rather than anything that has to look ahead.
 *
 * Run count is small (a memtable or three plus one segment per level), so picking the minimum
 * by linear scan beats a heap and allocates nothing per step.
 */
internal class MergingCursor(
    private val runs: Array<Run>,
    private val prefix: ByteArray,
    private val snapshotSequence: Long,
    private val onClose: () -> Unit,
) : EngineCursor {
    private var currentKey: ByteArray? = null
    private var currentValue: MemorySegment? = null

    override fun next(): Boolean {
        while (true) {
            val first = minimum() ?: return finish()
            val userKey = runs[first].userKeyBytes()
            if (!startsWith(userKey, prefix)) return finish()

            var found = false
            var deletion = false
            var value: MemorySegment? = null

            for (index in runs.indices) {
                val run = runs[index]
                while (run.valid() && sameUserKey(index, userKey)) {
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
                if (run.valid() && sameUserKey(index, userKey)) {
                    run.next()
                    if (run.valid() && sameUserKey(index, userKey)) run.skipPast(userKey)
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

    override fun close() {
        onClose()
    }

    private fun finish(): Boolean {
        currentKey = null
        currentValue = null
        return false
    }

    private fun minimum(): Int? {
        var best = -1
        for (i in runs.indices) {
            if (!runs[i].valid()) continue
            if (best < 0 || compare(i, best) < 0) best = i
        }
        return if (best < 0) null else best
    }

    private fun compare(left: Int, right: Int): Int = SegmentCompare.compare(
        runs[left].keySegment(), runs[left].keyOffset(), runs[left].keyLength(),
        runs[right].keySegment(), runs[right].keyOffset(), runs[right].keyLength(),
    )

    private fun sameUserKey(index: Int, userKey: ByteArray): Boolean =
        runs[index].userKeyLength() == userKey.size &&
            SegmentCompare.compare(runs[index].keySegment(), runs[index].keyOffset(), userKey.size, userKey) == 0

    private fun startsWith(key: ByteArray, prefix: ByteArray): Boolean =
        key.size >= prefix.size && java.util.Arrays.equals(key, 0, prefix.size, prefix, 0, prefix.size)
}
