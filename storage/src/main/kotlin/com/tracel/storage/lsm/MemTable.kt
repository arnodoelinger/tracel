package com.tracel.storage.lsm

import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import com.tracel.storage.ffm.SegmentCompare
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle
import java.util.SplittableRandom

/**
 * A skip list living entirely inside one off-heap arena.
 *
 * Nodes, keys and values are all bump-allocated into the same [MemorySegment], which buys two
 * things worth having: a memetable that costs the collector nothing no matter how many million
 * entries pass through it, and a hard byte ceiling — [put] returns `false` when the arena is
 * full, which is exactly the signal to freeze and flush.
 *
 * One writer. One arena. One publisher.
 *
 * Node layout:
 * ```
 * +0   i32   keyLen (internal key: user key + 8-byte trailer)
 * +4   i32   valueLen, -1 for a deletion
 * +8   i32   height
 * +12  i32   -
 * +16  i64   next[height]
 * ...        key bytes, then value bytes
 * ```
 */
class MemTable(capacityBytes: Long) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val data: MemorySegment = arena.allocate(capacityBytes, 64)
    private val random = SplittableRandom()

    // This starts at 8, not 0, and that 8 is not a random-looking magic number you can round
    // away. It is the one number that keeps this skip list from silently corrupting itself.
    //
    // Every next pointer in this structure uses 0 to mean "there is nothing here!1! this is the
    // end of the list". That convention only works as long as 0 is not also a valid offset that a
    // real node could be allocated at. The very first version of this bump pointer started at
    // 0, on the entirely reasonable assumption that an empty arena begins at its own start.
    // It does — but that means the very first node allocated would have landed at offset 0,
    // and every next pointer set to point at it would then be indistinguishable from a pointer
    // to nothing at all.
    //
    // The list would look empty after inserting into it, or lose whatever was linked through
    // that node, depending on which check happened to run first. Nothing throws when this happens.
    // It just quietly returns wrong answers, which is worse... Bumping the starting offset past
    // the one value that means "null" costs eight bytes out of however many megabytes this memtable
    // is configured for, which is not a tradeoff worth thinking about twice.
    private var used: Long = 8
    private val head: Long

    var maxSequence: Long = 0; private set

    var walId: Long = 0

    var entries: Int = 0; private set

    val capacity: Long get() = data.byteSize()
    val bytesUsed: Long get() = used

    init {
        head = allocate(nodeBytes(MAX_HEIGHT, 0, 0))
        data.putI32(head, 0)
        data.putI32(head + 4, -1)
        data.putI32(head + 8, MAX_HEIGHT)
        for (level in 0 until MAX_HEIGHT) setNext(head, level, 0L)
    }

    /**
     * Inserts one version. Returns `false` — having changed nothing — when the arena cannot
     * hold it, which is the caller's cue to freeze this memtable and start another.
     */
    fun put(userKey: ByteArray, value: ByteArray?, sequence: Long): Boolean {
        val keyLength = userKey.size + InternalKey.TRAILER_BYTES
        val valueLength = value?.size ?: 0

        // TODO: review this optimization
        var height = randomHeight()
        var needed = nodeBytes(height, keyLength, valueLength)
        if (used + needed > data.byteSize()) {
            height = 1
            needed = nodeBytes(height, keyLength, valueLength)
        }
        if (used + needed > data.byteSize()) return false

        val previous = LongArray(MAX_HEIGHT)
        val internal = InternalKey.encode(userKey, sequence, if (value == null) InternalKey.TYPE_DELETE else InternalKey.TYPE_VALUE)
        findPrevious(internal, previous)

        val node = allocate(needed)
        data.putI32(node, keyLength)
        data.putI32(node + 4, if (value == null) -1 else valueLength)
        data.putI32(node + 8, height)
        data.putI32(node + 12, 0)
        val keyAt = node + 16 + height * 8L
        data.writeBytes(keyAt, internal)
        if (value != null) data.writeBytes(keyAt + keyLength, value)

        // Link bottom-up: a reader crossing level 0 sees a complete node, and one that has
        // already passed a higher level simply arrives by a longer route.
        for (level in 0 until height) {
            setNextPlain(node, level, nextOf(previous[level], level))
        }
        for (level in 0 until height) {
            setNext(previous[level], level, node)
        }

        entries++
        if (sequence > maxSequence) maxSequence = sequence
        return true
    }

    /**
     * The newest version of [userKey] at or below [snapshotSequence].
     *
     * @return the node offset, or [NOT_FOUND]. A caller reads [valueOf] to tell a value from a
     * deletion; collapsing them here would make "deleted" and "never existed" the same answer,
     * and in an `LSM` those two must stay distinct — one shadows the segments below, the other
     * does not.
     */
    fun find(userKey: ByteArray, snapshotSequence: Long): Long {
        var node = seek(InternalKey.seekTarget(userKey))
        while (node != 0L) {
            val keyLength = data.i32(node)
            val userLength = InternalKey.userKeyLength(keyLength)
            if (userLength != userKey.size ||
                SegmentCompare.compare(data, keyOffset(node), userLength, userKey) != 0
            ) {
                return NOT_FOUND
            }
            if (sequenceOf(node) <= snapshotSequence) return node
            node = nextOf(node, 0)
        }
        return NOT_FOUND
    }

    fun seek(internalKey: ByteArray): Long {
        var node = head
        for (level in MAX_HEIGHT - 1 downTo 0) {
            var next = nextOf(node, level)
            while (next != 0L && compareInternal(next, internalKey) < 0) {
                node = next
                next = nextOf(node, level)
            }
        }
        return nextOf(node, 0)
    }

    fun nextOf(node: Long, level: Int): Long = NEXT.getAcquire(data, node + 16 + level * 8L) as Long

    fun keyOffset(node: Long): Long = node + 16 + heightOf(node) * 8L

    fun keyLength(node: Long): Int = data.i32(node)

    fun userKeyLength(node: Long): Int = InternalKey.userKeyLength(data.i32(node))

    fun sequenceOf(node: Long): Long = InternalKey.sequenceOf(trailerOf(node))

    fun isDeletion(node: Long): Boolean = data.i32(node + 4) == -1

    fun valueOf(node: Long): MemorySegment? {
        val length = data.i32(node + 4)
        if (length < 0) return null
        return data.asSlice(keyOffset(node) + keyLength(node), length.toLong()).asReadOnly()
    }

    fun userKeyBytes(node: Long): ByteArray = data.readBytes(keyOffset(node), userKeyLength(node))

    val segment: MemorySegment get() = data

    override fun close() {
        if (arena.scope().isAlive) arena.close()
    }

    private fun trailerOf(node: Long): Long =
        java.lang.Long.reverseBytes(data.i64(keyOffset(node) + userKeyLength(node)))

    private fun heightOf(node: Long): Int = data.i32(node + 8)

    private fun compareInternal(node: Long, internalKey: ByteArray): Int =
        SegmentCompare.compare(data, keyOffset(node), keyLength(node), internalKey)

    private fun findPrevious(internalKey: ByteArray, into: LongArray) {
        var node = head
        for (level in MAX_HEIGHT - 1 downTo 0) {
            var next = nextOf(node, level)
            while (next != 0L && compareInternal(next, internalKey) < 0) {
                node = next
                next = nextOf(node, level)
            }
            into[level] = node
        }
    }

    private fun allocate(bytes: Long): Long {
        val at = used
        used += (bytes + 7) and 7L.inv() // 8-byte aligned
        return at
    }

    private fun nodeBytes(height: Int, keyLength: Int, valueLength: Int): Long =
        16L + height * 8L + keyLength + valueLength

    private fun setNext(node: Long, level: Int, target: Long) {
        NEXT.setRelease(data, node + 16 + level * 8L, target)
    }

    private fun setNextPlain(node: Long, level: Int, target: Long) {
        data.putI64(node + 16 + level * 8L, target)
    }

    private fun randomHeight(): Int {
        var height = 1
        while (height < MAX_HEIGHT && (random.nextInt() and 3) == 0) height++
        return height
    }

    companion object {
        const val NOT_FOUND = -1L
        private const val MAX_HEIGHT = 12
        private val NEXT: VarHandle = ValueLayout.JAVA_LONG.varHandle()
    }
}
