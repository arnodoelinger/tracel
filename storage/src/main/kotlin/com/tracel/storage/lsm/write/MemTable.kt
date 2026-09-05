package com.tracel.storage.lsm.write

import com.tracel.storage.ffm.Bytes
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.ffm.Bytes.writeBytes
import com.tracel.storage.ffm.SegmentCompare
import com.tracel.storage.lsm.InternalKey
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * A skip list living entirely inside one off-heap arena.
 *
 * Better never touch this.
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
 * +4   i32   height
 * +8   i32   valueLen, -1 for a deletion
 * +12  i32   -
 * +16  i64   next[height]
 * ...        key bytes, then value bytes
 * ```
 */
class MemTable(capacityBytes: Long) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val data: MemorySegment = arena.allocate(capacityBytes, 64)

    // xorshift32
    private var rng: Int = (SEEDS.getAndIncrement() * GOLDEN) or 1

    // Writer-only scratch. Both of these used to be allocated fresh on every single put: a
    // 96-byte LongArray that the JVM then had to zero, and an internal-key ByteArray. One
    // writer thread owns this memtable, which is what makes reusing them safe, and it is also
    // the only reason it is safe — do not reach for either of these from a reader.
    private val previous = LongArray(MAX_HEIGHT)
    private var keyScratch = ByteArray(64)

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
    private val head: Long = used

    var maxSequence: Long = 0; private set

    var walId: Long = 0

    var entries: Int = 0; private set

    val capacity: Long get() = data.byteSize()
    val bytesUsed: Long get() = used

    init {
        used += (16L + (MAX_HEIGHT shl 3) + 7) and -8L
        data.putI64(head, MAX_HEIGHT.toLong() shl 32)
        data.putI64(head + 8, DELETION_MARK)
        for (level in 0 until MAX_HEIGHT) setNext(head, level, 0L)
    }

    /**
     * Inserts one version. Returns `false` — having changed nothing — when the arena cannot
     * hold it, which is the caller's cue to freeze this memtable and start another.
     */
    fun put(userKey: ByteArray, value: ByteArray?, sequence: Long): Boolean {
        val keyLength = userKey.size + InternalKey.TRAILER_BYTES
        val valueLength = value?.size ?: 0
        val ceiling = data.byteSize()

        var height = randomHeight()
        var needed = (16L + (height.toLong() shl 3) + keyLength + valueLength + 7) and -8L
        if (used + needed > ceiling) {
            height = 1
            needed = (24L + keyLength + valueLength + 7) and -8L
        }
        if (used + needed > ceiling) return false

        encodeInto(userKey, sequence, if (value == null) InternalKey.TYPE_DELETE else InternalKey.TYPE_VALUE)
        findPrevious(keyScratch, keyLength)

        val node = used
        used += needed

        // Two i64 stores for a four-field header. The alignment padding at +12 was already
        // being written; it just used to cost its own instruction.
        data.putI64(node, (keyLength.toLong() and 0xFFFFFFFFL) or (height.toLong() shl 32))
        data.putI64(node + 8, if (value == null) DELETION_MARK else valueLength.toLong() and 0xFFFFFFFFL)

        val keyAt = node + 16L + (height.toLong() shl 3)
        MemorySegment.copy(keyScratch, 0, data, Bytes.I8, keyAt, keyLength)
        if (value != null) data.writeBytes(keyAt + keyLength, value)

        // Link bottom-up: a reader crossing level 0 sees a complete node, and one that has
        // already passed a higher level simply arrives by a longer route.
        val links = node + 16L
        for (level in 0 until height) {
            data.putI64(links + (level shl 3), nextPlain(previous[level], level))
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
     * A caller reads [valueOf] to tell a value from a deletion.
     *
     * The search target is the user key itself, with no trailer appended and so with nothing
     * allocated. Comparing a node's full internal key against a bare user key gives the same
     * ordering as comparing it against `userKey || 0x00 * 8`: the shared prefix decides, and
     * when it does not, both forms agree that the node sorts after the target. Readers run this
     * concurrently and on arbitrary threads, so a reusable buffer is not on the table here.
     *
     * @return the node offset, or [NOT_FOUND].
     */
    fun find(userKey: ByteArray, snapshotSequence: Long): Long {
        val userLength = userKey.size
        var node = head
        var level = MAX_HEIGHT - 1
        while (level >= 0) {
            var next = nextOf(node, level)
            while (next != 0L && compareInternal(next, userKey, userLength) < 0) {
                node = next
                next = nextOf(node, level)
            }
            level--
        }

        node = nextOf(node, 0)
        while (node != 0L) {
            val header = data.i64(node)
            if (header.toInt() - InternalKey.TRAILER_BYTES != userLength) return NOT_FOUND
            val keyAt = keyOffsetOf(node, header)
            if (SegmentCompare.compare(data, keyAt, userLength, userKey, userLength) != 0) return NOT_FOUND
            val trailer = java.lang.Long.reverseBytes(data.i64(keyAt + userLength))
            if (InternalKey.sequenceOf(trailer) <= snapshotSequence) return node
            node = nextOf(node, 0)
        }
        return NOT_FOUND
    }

    /** The first node whose internal key is not less than [internalKey]. */
    fun seek(internalKey: ByteArray): Long = seek(internalKey, internalKey.size)

    /**
     * The first node whose internal key is not less than [internalKey].
     *
     * This is the same as [seek], but the caller has already computed the length of the internal
     * key and so does not need to allocate a temporary array just to pass it in.
     */
    fun seek(internalKey: ByteArray, length: Int): Long {
        var node = head
        var level = MAX_HEIGHT - 1
        while (level >= 0) {
            var next = nextOf(node, level)
            while (next != 0L && compareInternal(next, internalKey, length) < 0) {
                node = next
                next = nextOf(node, level)
            }
            level--
        }
        return nextOf(node, 0)
    }

    /** The next node at [level] after [node], or 0 if there is none. */
    fun nextOf(node: Long, level: Int): Long = NEXT.getAcquire(data, node + 16L + (level shl 3)) as Long

    /** The header of [node], which encodes its key length and height. */
    fun header(node: Long): Long = data.i64(node)

    /**
     * The offset of the key bytes of [node], which is 16 bytes past the node's start plus
     * 8 bytes for every level it has.
     */
    fun keyOffsetOf(node: Long, header: Long): Long = node + 16L + ((header ushr 32) shl 3)

    /** The length of the key bytes of node, which is the header's low 32 bits. */
    fun keyLengthOf(header: Long): Int = header.toInt()

    /** The offset of the key bytes of [node], which is 16 bytes past the node's start plus
     * 8 bytes for every level it has.
     */
    fun keyOffset(node: Long): Long = keyOffsetOf(node, data.i64(node))

    /** The length of the key bytes of [node], which is the header's low 32 bits.
     */
    fun keyLength(node: Long): Int = data.i32(node)

    /**
     * The offset of the value bytes of [node], which is 16 bytes past the node's start plus
     * 8 bytes for every level it has, plus the key length.
     */
    fun sequenceOf(node: Long): Long {
        val header = data.i64(node)
        val userLength = (header.toInt() - InternalKey.TRAILER_BYTES).toLong()
        return InternalKey.sequenceOf(java.lang.Long.reverseBytes(data.i64(keyOffsetOf(node, header) + userLength)))
    }

    /**
     * The offset of the value bytes of [node], which is 16 bytes past the node's start +
     * 8 bytes for every level it has + the key length.
     */
    fun isDeletion(node: Long): Boolean = data.i32(node + 8) == -1

    /** @return the value bytes of [node], or null if it is a deletion. */
    fun valueOf(node: Long): MemorySegment? {
        val header = data.i64(node)
        val length = data.i32(node + 8)
        if (length < 0) return null
        return data.asSlice(keyOffsetOf(node, header) + keyLengthOf(header), length.toLong()).asReadOnly()
    }

    /** @return the user key bytes of [node]. */
    fun userKeyBytes(node: Long): ByteArray {
        val header = data.i64(node)
        return data.readBytes(keyOffsetOf(node, header), header.toInt() - InternalKey.TRAILER_BYTES)
    }

    val segment: MemorySegment get() = data

    override fun close() {
        if (arena.scope().isAlive) arena.close()
    }

    private fun nextPlain(node: Long, level: Int): Long = NEXT.get(data, node + 16L + (level shl 3)) as Long

    private fun compareInternal(node: Long, key: ByteArray, length: Int): Int {
        val header = data.i64(node)
        return SegmentCompare.compare(data, keyOffsetOf(node, header), header.toInt(), key, length)
    }

    private fun findPrevious(internalKey: ByteArray, length: Int) {
        val into = previous
        var node = head
        var level = MAX_HEIGHT - 1
        while (level >= 0) {
            var next = nextPlain(node, level)
            while (next != 0L && compareInternal(next, internalKey, length) < 0) {
                node = next
                next = nextPlain(node, level)
            }
            into[level] = node
            level--
        }
    }

    // userKey || BE64(trailer) -> keyScratch. Writer-only
    private fun encodeInto(userKey: ByteArray, sequence: Long, type: Byte) {
        val length = userKey.size + InternalKey.TRAILER_BYTES
        var buffer = keyScratch
        if (buffer.size < length) {
            buffer = ByteArray(length + 32)
            keyScratch = buffer
        }
        System.arraycopy(userKey, 0, buffer, 0, userKey.size)
        BE_LONG.set(buffer, userKey.size, InternalKey.trailer(sequence, type))
    }

    private fun setNext(node: Long, level: Int, target: Long) {
        NEXT.setRelease(data, node + 16L + (level shl 3), target)
    }

    // p 1/4, capped at max height, out of one xorshift step
    private fun randomHeight(): Int {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rng = x
        return 1 + (Integer.numberOfTrailingZeros(x or HEIGHT_STOP) shr 1)
    }

    companion object {
        const val NOT_FOUND = -1L
        private const val MAX_HEIGHT = 12

        private val NEXT: VarHandle = ValueLayout.JAVA_LONG.varHandle()
        private val BE_LONG: VarHandle =
            MethodHandles.byteArrayViewVarHandle(LongArray::class.java, ByteOrder.BIG_ENDIAN)
        const val MAX_ENTRY_OVERHEAD: Long = 16L + MAX_HEIGHT * 8L + InternalKey.TRAILER_BYTES + 7L

        private const val DELETION_MARK = 0xFFFFFFFFL

        private const val HEIGHT_STOP = 1 shl ((MAX_HEIGHT - 1) * 2)

        private const val GOLDEN = -0x61c88647
        private val SEEDS = AtomicInteger(1)
    }
}
