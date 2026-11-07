package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.bits.ByteWriter
import com.tracel.storage.chrono.bits.LittleEndian
import com.tracel.storage.chrono.bits.SegmentReader

/** Where an entity stood and which way it looked, as a capsule keeps it. */
internal object Poses {
    private const val POSITION = 1024.0
    private const val ANGLE = 128.0f
    private const val LIMIT = 1e12
    private const val X = 1
    private const val Y = 9
    private const val Z = 17
    private const val YAW = 25
    private const val PITCH = 29

    class Writer {
        private val sink = ByteWriter(64)
        private var x = 0L
        private var y = 0L
        private var z = 0L

        fun add(blob: ByteArray) {
            val qx = quantize(Double.fromBits(LittleEndian.LONG.get(blob, X) as Long))
            val qy = quantize(Double.fromBits(LittleEndian.LONG.get(blob, Y) as Long))
            val qz = quantize(Double.fromBits(LittleEndian.LONG.get(blob, Z) as Long))
            sink.signed(qx - x)
            sink.signed(qy - y)
            sink.signed(qz - z)
            x = qx
            y = qy
            z = qz
            sink.signed(Math.round(Float.fromBits(LittleEndian.INT.get(blob, YAW) as Int) * ANGLE).toLong())
            sink.signed(Math.round(Float.fromBits(LittleEndian.INT.get(blob, PITCH) as Int) * ANGLE).toLong())
        }

        fun writeTo(out: ByteWriter) {
            val bytes = sink.toByteArray()
            out.varint(bytes.size.toLong())
            out.bytes(bytes)
        }
    }

    /** Whether the pose in [blob] is a finite number a capsule can keep. */
    fun fits(blob: ByteArray): Boolean {
        for (at in intArrayOf(X, Y, Z)) {
            val v = Double.fromBits(LittleEndian.LONG.get(blob, at) as Long)
            if (!v.isFinite() || v > LIMIT || v < -LIMIT) return false
        }
        for (at in intArrayOf(YAW, PITCH)) {
            val v = Float.fromBits(LittleEndian.INT.get(blob, at) as Int)
            if (!v.isFinite() || v > 1e6f || v < -1e6f) return false
        }
        return true
    }

    /** [count] poses at [cursor], each as the 32 bytes after a tag. */
    fun read(cursor: SegmentReader, count: Int): ByteArray {
        val length = cursor.varintInt()
        val end = cursor.at + length
        val out = ByteArray(count * 32)
        var x = 0L
        var y = 0L
        var z = 0L
        for (i in 0 until count) {
            x += cursor.signed()
            y += cursor.signed()
            z += cursor.signed()
            val at = i * 32
            LittleEndian.LONG.set(out, at, (x / POSITION).toBits())
            LittleEndian.LONG.set(out, at + 8, (y / POSITION).toBits())
            LittleEndian.LONG.set(out, at + 16, (z / POSITION).toBits())
            LittleEndian.INT.set(out, at + 24, (cursor.signed() / ANGLE).toBits())
            LittleEndian.INT.set(out, at + 28, (cursor.signed() / ANGLE).toBits())
        }
        check(cursor.at == end) { "poses end at ${cursor.at}, not at $end." }
        return out
    }

    private fun quantize(v: Double): Long = Math.round(v * POSITION)
}
