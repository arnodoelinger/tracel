package com.tracel.storage.ffm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle

/**
 * A bounded off-heap multi-producer / single-consumer ring of fixed-size slots.
 *
 * This is the only storage object a Minecraft server region thread ever touches. Claiming slots
 * is one `CAS`, filling them is plain stores into a [MemorySegment], publishing is one release store
 * per slot.
 *
 * No allocation, no lock, no `synchronized`, and — the part that actually matters —
 * no path on which a producer waits for anything.
 *
 * Slot layout, [SLOT_BYTES] each:
 * ```
 * // Stale while the slot is filling
 * +0   long   stamp    slot sequence + 1, release-stored last
 * //
 * +8   ..     payload  PAYLOAD_BYTES of caller-packed primitives
 * ```
 * A consumer at cursor `c` waits for `stamp == c + 1` on slot `c and mask`. Stamping with the
 * sequence.
 */
class OffHeapRing(capacitySlots: Int) : AutoCloseable {
    init {
        require(capacitySlots > 0 && Integer.bitCount(capacitySlots) == 1) {
            "ring capacity must be a power of two, was $capacitySlots"
        }
    }

    private val arena = Arena.ofShared()
    private val capacity = capacitySlots.toLong()
    private val mask = (capacitySlots - 1).toLong()

    /**
     * Control words and slots are separate allocations, each 128-byte aligned: the producers'
     * `CAS` target and the consumer's cursor sharing a cache line would turn every drain into a
     * line ping-pong against every region thread on the box.
     */
    private val control: MemorySegment = arena.allocate(CONTROL_BYTES, 128)
    private val slots: MemorySegment = arena.allocate(capacity * SLOT_BYTES, 128)

    /** Events the ring refused because it was full. Never resets — a counter that forgets is a lie. */
    val dropped: Long get() = getVolatile(control, DROPPED_OFFSET)

    /** Slots claimed but not yet handed back by the consumer. */
    val depth: Long get() = getVolatile(control, CLAIM_OFFSET) - getVolatile(control, RELEASE_OFFSET)

    val capacitySlots: Int get() = capacity.toInt()

    /** The segment the slots live in. Addressable only through [payloadOffset]. */
    val payload: MemorySegment get() = slots

    /**
     * Reserves [count] contiguous slots, or returns [CLAIM_FAILED] and counts one drop if the
     * ring cannot hold them.
     *
     * The free-space check sits *inside* the `CAS` loop on purpose: checking first and claiming
     * after would let two producers both see room for the last slot and both take it.
     */
    fun claim(count: Int): Long {
        require(count in 1..capacity) { "claim of $count slots does not fit a $capacity-slot ring" }
        while (true) {
            val head = getVolatile(control, CLAIM_OFFSET)
            if (head + count - getVolatile(control, RELEASE_OFFSET) > capacity) {
                getAndAdd(control, DROPPED_OFFSET, 1L)
                return CLAIM_FAILED
            }
            if (compareAndSet(control, CLAIM_OFFSET, head, head + count)) return head
        }
    }

    /** Byte offset of the payload of the slot backing [sequence]. */
    fun payloadOffset(sequence: Long): Long = (sequence and mask) * SLOT_BYTES + PAYLOAD_OFFSET

    /**
     * Makes [sequence] visible to the consumer. Release semantics: every payload store issued
     * before this call is visible to whoever reads the stamp with acquire.
     */
    fun publish(sequence: Long) {
        setRelease(slots, (sequence and mask) * SLOT_BYTES, sequence + 1)
    }

    /** True once [sequence] has been published. Acquire-paired with [publish]. */
    fun isPublished(sequence: Long): Boolean =
        getAcquire(slots, (sequence and mask) * SLOT_BYTES) == sequence + 1

    /** The consumer's cursor — the first sequence it has not yet consumed. */
    fun consumerCursor(): Long = getVolatile(control, RELEASE_OFFSET)

    /**
     * Hands every slot below [upTo] back to the producers. Called only once the consumer has
     * finished reading those payloads — a released slot may be overwritten immediately.
     */
    fun release(upTo: Long) {
        setRelease(control, RELEASE_OFFSET, upTo)
    }

    override fun close() {
        if (arena.scope().isAlive) arena.close()
    }

    private fun getVolatile(segment: MemorySegment, offset: Long): Long =
        LONG.getVolatile(segment, offset) as Long

    private fun getAcquire(segment: MemorySegment, offset: Long): Long =
        LONG.getAcquire(segment, offset) as Long

    private fun setRelease(segment: MemorySegment, offset: Long, value: Long) {
        LONG.setRelease(segment, offset, value)
    }

    @Suppress("SameParameterValue")
    private fun compareAndSet(segment: MemorySegment, offset: Long, expected: Long, value: Long): Boolean =
        LONG.compareAndSet(segment, offset, expected, value)

    @Suppress("SameParameterValue")
    private fun getAndAdd(segment: MemorySegment, offset: Long, delta: Long): Long =
        LONG.getAndAdd(segment, offset, delta) as Long

    companion object {
        /** 8-byte stamp + 24-byte payload — see `com.tracel.storage.codec.CaptureSlot`. */
        const val SLOT_BYTES = 32L
        const val PAYLOAD_OFFSET = 8L

        /** No slots were available; the caller drops the event. */
        const val CLAIM_FAILED = -1L

        private const val CLAIM_OFFSET = 0L
        private const val RELEASE_OFFSET = 128L
        private const val DROPPED_OFFSET = 256L
        private const val CONTROL_BYTES = 384L

        // Read before you touch it.
        //
        // JAVA_LONG and not JAVA_LONG_UNALIGNED. Every other numeric field in this
        // storage engine is deliberately unaligned, because records are packed tight and nothing
        // in them is guaranteed to land on an 8-byte boundary.
        //
        // This VarHandle is the one exception, on purpose: getVolatile, getAcquire, setRelease,
        // compareAndSet and getAndAdd are only available on a VarHandle whose layout the JVM
        // considers properly aligned. Ask an unaligned layout for compareAndSet, and it throws at
        // the call site, so the failure shows up nowhere near this line.
        //
        // Both the control block and the slot array are allocated 128-byte aligned specifically so this
        // VarHandle's alignment requirement is always satisfied.
        private val LONG: VarHandle = ValueLayout.JAVA_LONG.varHandle()
    }
}
