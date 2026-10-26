package com.tracel.storage.lsm.segment

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.nio.ByteOrder

/**
 * A block turned on its side before `zstd` even sees it.
 *
 * ```
 * [varint rows] ([varint keyLength][varint valueLength + 1]) per row
 * key planes, key tails, value planes, value tails
 * ```
 */
internal object BlockPlanes {
    /** [raw] as planes. Its restart table is dropped: [decode] can count to 32 on its own. */
    fun encode(raw: ByteArray, length: Int): ByteArray {
        val restarts = LE_INT.get(raw, length - 4) as Int
        val end = length - 4 - restarts * 4
        val keys = ArrayList<ByteArray>()
        val values = ArrayList<ByteArray?>()
        var last = EMPTY
        var at = 0
        while (at < end) {
            val shared = Varints.read(raw, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf)
            val unshared = Varints.read(raw, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf)
            val valueLength = Varints.read(raw, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf) - 1
            val key = last.copyOf(shared + unshared)
            System.arraycopy(raw, at, key, shared, unshared)
            at += unshared
            values += if (valueLength < 0) null else raw.copyOfRange(at, at + valueLength)
            at += maxOf(0, valueLength)
            keys += key
            last = key
        }

        val out = Out(length + 64)
        out.varint(keys.size)
        for (i in keys.indices) {
            out.varint(keys[i].size)
            out.varint((values[i]?.size ?: -1) + 1)
        }
        out.planes(keys)
        out.planes(values.map { it ?: EMPTY })
        return out.done()
    }

    /** The block [encode] was given. */
    fun decode(planes: ByteArray): ByteArray {
        var at = 0
        val rows = Varints.read(planes, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf)
        val keyLengths = IntArray(rows)
        val valueLengths = IntArray(rows)
        for (i in 0 until rows) {
            keyLengths[i] = Varints.read(planes, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf)
            valueLengths[i] = Varints.read(planes, at).also { at += Varints.sizeOf(it) }.let(Varints::valueOf) - 1
        }
        val keys = Array(rows) { ByteArray(keyLengths[it]) }
        val values = Array(rows) { ByteArray(maxOf(0, valueLengths[it])) }
        at = unplanes(planes, at, keys)
        unplanes(planes, at, values)

        val out = Out(planes.size + rows * 4 + 64)
        val starts = IntArray((rows + SegmentFile.RESTART_INTERVAL - 1) / SegmentFile.RESTART_INTERVAL)
        var last = EMPTY
        for (i in 0 until rows) {
            val key = keys[i]
            val restart = i % SegmentFile.RESTART_INTERVAL == 0
            if (restart) starts[i / SegmentFile.RESTART_INTERVAL] = out.size
            var shared = 0
            if (!restart) {
                val most = minOf(last.size, key.size)
                while (shared < most && last[shared] == key[shared]) shared++
            }
            out.varint(shared)
            out.varint(key.size - shared)
            out.varint(valueLengths[i] + 1)
            out.bytes(key, shared, key.size - shared)
            out.bytes(values[i], 0, values[i].size)
            last = key
        }
        for (start in starts) out.int(start)
        out.int(starts.size)
        return out.done()
    }

    private fun unplanes(planes: ByteArray, from: Int, rows: Array<ByteArray>): Int {
        var at = from
        val width = rows.minOfOrNull { it.size } ?: 0
        for (j in 0 until width) {
            var previous = 0
            for (row in rows) {
                previous = (previous + planes[at++]).toByte().toInt()
                row[j] = previous.toByte()
            }
        }
        for (row in rows) {
            System.arraycopy(planes, at, row, width, row.size - width)
            at += row.size - width
        }
        return at
    }

    private class Out(capacity: Int) {
        private var bytes = ByteArray(capacity)
        var size = 0
            private set

        private fun room(more: Int) {
            if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(size + more, bytes.size * 2))
        }

        fun varint(value: Int) {
            room(5)
            size = Varints.write(bytes, size, value)
        }

        fun int(value: Int) {
            room(4)
            LE_INT.set(bytes, size, value)
            size += 4
        }

        fun bytes(src: ByteArray, from: Int, length: Int) {
            room(length)
            System.arraycopy(src, from, bytes, size, length)
            size += length
        }

        fun planes(rows: List<ByteArray>) {
            val width = rows.minOfOrNull { it.size } ?: 0
            room(rows.sumOf { it.size })
            for (j in 0 until width) {
                var previous = 0
                for (row in rows) {
                    val b = row[j].toInt()
                    bytes[size++] = (b - previous).toByte()
                    previous = b
                }
            }
            for (row in rows) bytes(row, width, row.size - width)
        }

        fun done(): ByteArray = bytes.copyOf(size)
    }

    private val EMPTY = ByteArray(0)
    private val LE_INT: VarHandle = MethodHandles.byteArrayViewVarHandle(IntArray::class.java, ByteOrder.LITTLE_ENDIAN)
}
