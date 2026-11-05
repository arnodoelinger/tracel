package com.tracel.storage.spi

import java.lang.foreign.MemorySegment

/**
 * The one interface every storage port is written against.
 *
 * Every port is written against this and nothing else, so swapping the engine underneath
 * changes not a line of ledger code.
 *
 * Contract:
 * - Keys are compared unsigned-lexicographically. Nothing else is ever a valid ordering.
 * - Exactly one thread writes. Any number may read.
 * - [write] is the only durability boundary: everything in one batch becomes visible together
 *   or not at all, and `durable = true` means it survives losing the machine.
 * - History is filed in segments by category and window. The history calls work on whole
 *   segments, and a snapshot taken before one of them keeps reading what it pinned.
 */
interface KeyValueEngine : AutoCloseable {
    val name: String

    // Reads and writes

    /** Applies [batch] atomically. */
    fun write(batch: MutationBatch, durable: Boolean)

    /**
     * A consistent view. Everything committed before the call is in it;
     * nothing after is.
     */
    fun snapshot(): EngineSnapshot

    /** Sends everything still buffered to stable storage. */
    fun sync()

    /** Why [write] is refusing right now, or `null` while it takes writes. */
    val stopped: WritesStopped? get() = null

    // Maintenance

    /**
     * Writes what is still in memory out as segments,
     * so that all of it is history a purge can see.
     */
    fun flush()

    /** Flushes and compacts everything, then waits for it. */
    fun compactEverything()

    /** Throws the whole store away and leaves an empty one behind. */
    fun wipe() = wipe(null)

    /** Throws everything away, and leaves [keep] in its place. */
    fun wipe(keep: MutationBatch?)

    /** Numbers worth putting in a bug report. */
    fun stats(): EngineStats

    // Segments of history

    /** The segments of history of one [category], newest window last. */
    fun history(category: Int): List<HistorySegment>

    /**
     * Throws away every segment of [category] whose window ended at
     * or before [endedBefore], as files: nothing is read, nothing is
     * rewritten, and the space is back when this returns.
     */
    fun dropHistory(category: Int, endedBefore: Long): Dropped

    /**
     * Reads what is under [prefix] in segment [id] and nothing else;
     * `null` if the segment is gone.
     */
    fun <T> readSegment(id: Long, prefix: ByteArray, read: (EngineCursor) -> T): T?

    /**
     * Replaces segment [id] with one that has only the rows [keep] says yes to,
     * which is how a purge takes some of a window and leaves the rest.
     */
    fun rewriteSegment(id: Long, keep: (key: ByteArray, value: MemorySegment?) -> Boolean): Rewritten
}
