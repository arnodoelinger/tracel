package com.tracel.storage.ffm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.VarHandle

/**
 * A bounded off-heap multi-producer / single-consumer ring of fixed-size slots.
 *
 * Better never touch ts.
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

    private val control: MemorySegment = arena.allocate(CONTROL_BYTES, 128)
    private val slots: MemorySegment = arena.allocate(capacity * SLOT_BYTES, 128)

    val dropped: Long get() = getVolatile(control, DROPPED_OFFSET)

    val payload: MemorySegment get() = slots

    /**
     * Reserves [count] contiguous slots, or returns [CLAIM_FAILED] and counts one drop if the
     * ring cannot hold them.
     *
     * The free-space check sits inside the `CAS` loop on purpose: checking first and claiming
     * after would let two producers both see room for the last slot and both take it.
     *
     * Three things here are load-bearing, and none of them are decoration.
     *
     * The check reads [GATE_OFFSET]. The release cursor is a line the consumer writes — every
     * producer that reads it takes a coherence miss, on every claim, whether the ring is anywhere
     * near full or not. The gate is a conservative cached copy of it, and it is deliberately
     * parked in the same 128-byte block as the claim cursor: a producer about to `CAS` that line
     * already owns it exclusively, so reading the gate off it costs nothing at all.
     *
     * The real cursor is only touched when the gate says the ring might be full, which is the one
     * case where the miss is worth paying for. The gate can only ever lag the truth, and lagging
     * makes the check stricter, never looser — a stale gate can cost a spurious refresh, never a
     * double-claimed slot.
     */
    fun claim(count: Int): Long {
        // Not count in 1..capacity, don't change this
        if (count < 1 || count > capacity) {
            throw IllegalArgumentException("claim of $count slots does not fit a $capacity-slot ring")
        }
        val want = count.toLong()
        var head = getVolatile(control, CLAIM_OFFSET)
        while (true) {
            val wrap = head + want - capacity
            if (wrap > getOpaque(control, GATE_OFFSET)) {
                val release = getVolatile(control, RELEASE_OFFSET)
                setOpaque(control, GATE_OFFSET, release)
                if (wrap > release) {
                    getAndAdd(control, DROPPED_OFFSET, 1L)
                    return CLAIM_FAILED
                }
            }
            val witness = compareAndExchange(control, CLAIM_OFFSET, head, head + want)
            if (witness == head) return head
            head = witness
            Thread.onSpinWait()
        }
    }

    /** Byte offset of the payload of the slot backing [sequence]. */
    fun payloadOffset(sequence: Long): Long = ((sequence and mask) shl SLOT_SHIFT) + PAYLOAD_OFFSET

    /**
     * Makes [sequence] visible to the consumer. Release semantics: every payload store issued
     * before this call is visible to whoever reads the stamp with acquire.
     */
    fun publish(sequence: Long) {
        setRelease(slots, (sequence and mask) shl SLOT_SHIFT, sequence + 1)
    }

    /** True once [sequence] has been published. Acquire-paired with [publish]. */
    fun isPublished(sequence: Long): Boolean =
        getAcquire(slots, (sequence and mask) shl SLOT_SHIFT) == sequence + 1

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

    @Suppress("SameParameterValue")
    private fun getOpaque(segment: MemorySegment, offset: Long): Long =
        LONG.getOpaque(segment, offset) as Long

    @Suppress("SameParameterValue")
    private fun setOpaque(segment: MemorySegment, offset: Long, value: Long) {
        LONG.setOpaque(segment, offset, value)
    }

    private fun setRelease(segment: MemorySegment, offset: Long, value: Long) {
        LONG.setRelease(segment, offset, value)
    }

    @Suppress("SameParameterValue")
    private fun compareAndExchange(segment: MemorySegment, offset: Long, expected: Long, value: Long): Long =
        LONG.compareAndExchange(segment, offset, expected, value) as Long

    @Suppress("SameParameterValue")
    private fun getAndAdd(segment: MemorySegment, offset: Long, delta: Long): Long =
        LONG.getAndAdd(segment, offset, delta) as Long

    companion object {
        const val SLOT_BYTES = 32L
        const val PAYLOAD_OFFSET = 8L
        const val CLAIM_FAILED = -1L

        private const val SLOT_SHIFT = 5

        // The control block, laid out by who writes what.
        //
        // 128 and not 64. Intel's L2 spatial prefetcher pulls cache lines in aligned pairs, so
        // two words 64 bytes apart still ride into the same core together and still ping-pong.
        // The whole point of this layout is that they do not.
        //
        // [0,128)   CLAIM + GATE — written by every producer, and only by producers.
        //                          The gate shares this block and a producer holds the
        //                          line exclusively for the CAS anyway, so the gate read is free.
        // [128,256) RELEASE      — written by the consumer, read by producers only when the
        //                          gate says the ring may be full.
        // [256,384) DROPPED      — written by producers, and only when an event is being lost.
        //                          Off the claim line so a full ring cannot slow down the claim
        //                          of whoever still fits.
        // [384,512) tail padding — nothing of ours follows, but nothing of anyone else's gets to
        //                          share the dropped counter's line pair either.
        private const val CLAIM_OFFSET = 0L
        private const val GATE_OFFSET = 8L
        private const val RELEASE_OFFSET = 128L
        private const val DROPPED_OFFSET = 256L
        private const val CONTROL_BYTES = 512L

        // Read before you touch it.
        //
        // JAVA_LONG and not JAVA_LONG_UNALIGNED. Every other numeric field in this
        // storage engine is deliberately unaligned, because records are packed tight and nothing
        // in them is guaranteed to land on an 8-byte boundary.
        //
        // This VarHandle is the one exception, on purpose: getVolatile, getAcquire, setRelease,
        // compareAndExchange and getAndAdd are only available on a VarHandle whose layout the JVM
        // considers properly aligned. Ask an unaligned layout for compareAndExchange, and it
        // throws at the call site, so the failure shows up nowhere near this line.
        //
        // Both the control block and the slot array are allocated 128-byte aligned specifically so this
        // VarHandle's alignment requirement is always satisfied.
        private val LONG: VarHandle = ValueLayout.JAVA_LONG.varHandle()
    }
}
