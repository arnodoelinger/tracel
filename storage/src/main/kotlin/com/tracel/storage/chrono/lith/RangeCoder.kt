package com.tracel.storage.chrono.lith

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** A binary range coder over adaptive bit models, the engine under a lithified capsule. */
internal abstract class Coder(slots: Int) {
    private val probs = IntArray(slots) { HALF_PAIR }
    private val seen = ByteArray(slots)

    /** Whether values passed in are ignored and the decoded ones come back. */
    abstract val decoding: Boolean

    /** Codes [bit] under model [slot]; says the bit. */
    abstract fun bit(slot: Int, bit: Int): Int

    /** Codes [bit] at even odds, with no model. */
    abstract fun raw(bit: Int): Int

    protected fun p15(slot: Int): Int {
        val v = probs[slot]
        val p = ((v ushr 16) + (v and 0xFFFF)) ushr 2
        return p.coerceIn(MIN_P, ONE - MIN_P)
    }

    protected fun learn(slot: Int, bit: Int) {
        val v = probs[slot]
        var fast = v ushr 16
        var slow = v and 0xFFFF
        val n = seen[slot].toInt()
        if (bit == 0) {
            fast += (((0xFFFF - fast).toLong() * FAST[n]) shr 16).toInt()
            slow += (((0xFFFF - slow).toLong() * SLOW[n]) shr 16).toInt()
        } else {
            fast -= ((fast.toLong() * FAST[n]) shr 16).toInt()
            slow -= ((slow.toLong() * SLOW[n]) shr 16).toInt()
        }
        probs[slot] = (fast shl 16) or slow
        if (n < 63) seen[slot] = (n + 1).toByte()
    }

    /** [bits] low bits of [v], most significant first, at even odds. */
    fun raw(bits: Int, v: Long): Long {
        var out = 0L
        for (s in bits - 1 downTo 0) out = (out shl 1) or raw(((v ushr s) and 1L).toInt()).toLong()
        return out
    }

    /** [bits] bits of [v] down a tree of models under [base]; takes `1 shl bits` slots. */
    fun tree(base: Int, bits: Int, v: Int): Int {
        var node = 1
        for (s in bits - 1 downTo 0) node = (node shl 1) or bit(base + node, (v ushr s) and 1)
        return node - (1 shl bits)
    }

    /** A non-negative number, Elias-gamma style. */
    fun gamma(base: Int, v: Long): Long {
        val x = v + 1
        val k = if (decoding) 0 else 63 - x.countLeadingZeroBits()
        var len = 0
        while (bit(base + minOf(len, 63), if (len < k) 1 else 0) == 1) len++
        if (len == 0) return 0
        var out = 1L
        for (s in len - 1 downTo 0) {
            val wanted = if (decoding) 0 else ((x ushr s) and 1L).toInt()
            val b = when (len - 1 - s) {
                0 -> bit(base + 64 + len, wanted)
                1 -> bit(base + 128 + len * 2 + (out and 1L).toInt(), wanted)
                else -> raw(wanted)
            }
            out = (out shl 1) or b.toLong()
        }
        return out - 1
    }

    /**
     * A signed number: a sign under [base], then [gamma] of the magnitude.
     *
     * Takes [SIGNED] slots.
     */
    fun signed(base: Int, v: Long): Long {
        val negative = bit(base, if (v < 0) 1 else 0)
        val magnitude = gamma(base + 1, if (v < 0) -v - 1 else v)
        return if (negative == 1) -magnitude - 1 else magnitude
    }

    companion object {
        const val EG: Int = 256
        const val SIGNED: Int = EG + 1

        private const val ONE = 32768
        private const val MIN_P = 16
        private const val HALF_PAIR = (32768 shl 16) or 32768

        private val FAST = IntArray(64) { maxOf(65536 / (it + 2), 65536 shr 4) }
        private val SLOW = IntArray(64) { maxOf(65536 / (it + 2), 65536 shr 7) }
    }
}

/** Writes bits into a growing byte array, carries and all. */
internal class Encoder(slots: Int) : Coder(slots) {
    private var out = ByteArray(4096)
    private var size = 0
    private var low = 0L
    private var range = 0xFFFF_FFFFL
    private var cache = 0
    private var pending = 1L

    override val decoding: Boolean get() = false

    override fun bit(slot: Int, bit: Int): Int {
        val bound = (range ushr 15) * p15(slot)
        if (bit == 0) range = bound else {
            low += bound
            range -= bound
        }
        while (range < TOP) {
            range = range shl 8
            shiftLow()
        }
        learn(slot, bit)
        return bit
    }

    override fun raw(bit: Int): Int {
        range = range ushr 1
        if (bit == 1) low += range
        while (range < TOP) {
            range = range shl 8
            shiftLow()
        }
        return bit
    }

    /** The bytes so far, flushed; the coder is spent after this. */
    fun finish(): ByteArray {
        repeat(5) { shiftLow() }
        return out.copyOf(size)
    }

    private fun shiftLow() {
        if (low !in 0xFF00_0000L..0xFFFF_FFFFL) {
            val carry = (low ushr 32).toInt()
            var c = cache
            do {
                put(c + carry)
                c = 0xFF
            } while (--pending != 0L)
            cache = ((low ushr 24) and 0xFF).toInt()
        }
        pending++
        low = (low and 0x00FF_FFFFL) shl 8
    }

    private fun put(b: Int) {
        if (size == out.size) out = out.copyOf(size * 2)
        out[size++] = b.toByte()
    }

    private companion object {
        const val TOP = 1L shl 24
    }
}

/** Reads what an [Encoder] wrote, from [at] until [end] of [seg]; past the end it reads zeros. */
internal class Decoder(slots: Int, private val seg: MemorySegment, private var at: Long, private val end: Long) :
    Coder(slots) {
    private var range = 0xFFFF_FFFFL
    private var code = 0L

    init {
        at++ // The encoder's first byte is its empty cache
        repeat(4) { code = (code shl 8) or next().toLong() }
    }

    override val decoding: Boolean get() = true

    override fun bit(slot: Int, bit: Int): Int {
        val bound = (range ushr 15) * p15(slot)
        val out: Int
        if (code < bound) {
            range = bound
            out = 0
        } else {
            code -= bound
            range -= bound
            out = 1
        }
        while (range < TOP) {
            range = range shl 8
            code = ((code shl 8) or next().toLong()) and 0xFFFF_FFFFL
        }
        learn(slot, out)
        return out
    }

    override fun raw(bit: Int): Int {
        range = range ushr 1
        var out = 0
        if (code >= range) {
            code -= range
            out = 1
        }
        while (range < TOP) {
            range = range shl 8
            code = ((code shl 8) or next().toLong()) and 0xFFFF_FFFFL
        }
        return out
    }

    private fun next(): Int = if (at < end) seg.get(ValueLayout.JAVA_BYTE, at++).toInt() and 0xFF else 0

    private companion object {
        const val TOP = 1L shl 24
    }
}
