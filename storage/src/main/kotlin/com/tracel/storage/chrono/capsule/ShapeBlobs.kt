package com.tracel.storage.chrono.capsule

import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.bits.*
import java.lang.foreign.MemorySegment

internal const val POSED: Byte = 1
internal const val POSE_BYTES = 33

/** The shapes of the rows in a capsule, as blobs: the before and after of each, `null` where there was none. */
internal object ShapeBlobs {
    fun write(out: ByteWriter, rows: Rows, order: IntArray) {
        val n = order.size
        val codes = IntArray(2 * n)
        val palette = LinkedHashMap<BlobKey, Int>()
        val groupOf = ArrayList<Int>()
        val indexes = IntArray(2 * n)
        val poses = Poses.Writer()
        fun side(slot: Int, blob: ByteArray?) {
            if (blob == null) return
            val posed = blob.size >= POSE_BYTES && blob[0] == POSED && Poses.fits(blob)
            codes[slot] = if (posed) 1 else 2
            val rest = if (posed) blob.copyOfRange(POSE_BYTES, blob.size) else blob
            if (posed) poses.add(blob)
            val known = palette.size
            val at = palette.getOrPut(BlobKey(rest)) { known }
            if (at == known) groupOf += rows.after[order[slot % n]]
            indexes[slot] = at
        }
        for (k in 0 until n) side(k, rows.beforeExtrasAt(order[k]))
        for (k in 0 until n) side(n + k, rows.afterExtrasAt(order[k]))
        BitPack.ints(out, codes, 2 * n, 2)
        poses.writeTo(out)
        writePalette(out, palette.keys.map { it.bytes }, groupOf)
        val used = IntArray(2 * n)
        var u = 0
        for (slot in 0 until 2 * n) if (codes[slot] != 0) used[u++] = indexes[slot]
        BitPack.ints(out, used, u, indexWidth(palette.size))
    }

    fun read(seg: MemorySegment, c: SegmentReader, n: Int): Array<ByteArray?> {
        val codes = IntArray(2 * n).also { BitUnpack.ints(seg, c.at, 2, 2 * n, it) }
        c.skip(packedBytes(2 * n, 2).toLong())
        val posed = codes.count { it == 1 }
        val poses = Poses.read(c, posed)
        val palette = arrayOfNulls<ByteArray>(c.varintInt())
        for (i in palette.indices) {
            val ref = c.varintInt()
            val length = c.varintInt()
            palette[i] = if (ref == 0) c.bytes(length) else ByteDelta.decode(c, palette[ref - 1]!!, length)
        }
        val used = codes.count { it != 0 }
        val indexes = IntArray(used).also { BitUnpack.ints(seg, c.at, indexWidth(palette.size), used, it) }
        var pose = 0
        var next = 0
        val shapes = arrayOfNulls<ByteArray>(2 * n)
        for (slot in 0 until 2 * n) {
            val code = codes[slot]
            if (code == 0) continue
            val rest = palette[indexes[next++]]!!
            shapes[slot] = if (code == 2) rest else ByteArray(POSE_BYTES + rest.size).also {
                it[0] = POSED
                poses.copyInto(it, 1, pose * (POSE_BYTES - 1), (pose + 1) * (POSE_BYTES - 1))
                rest.copyInto(it, POSE_BYTES)
                pose++
            }
        }
        return shapes
    }

    private fun writePalette(out: ByteWriter, blobs: List<ByteArray>, groups: List<Int>) {
        out.varint(blobs.size.toLong())
        val first = HashMap<Int, Int>()
        val last = HashMap<Int, Int>()
        val trial = ByteWriter(256)
        for (i in blobs.indices) {
            val blob = blobs[i]
            var ref = -1
            var cost = blob.size
            for (candidate in setOf(first[groups[i]] ?: -1, last[groups[i]] ?: -1)) {
                if (candidate < 0) continue
                trial.reset()
                ByteDelta.encode(blob, blobs[candidate], trial)
                if (trial.size + 2 < cost) {
                    ref = candidate
                    cost = trial.size + 2
                }
            }
            out.varint((ref + 1).toLong())
            out.varint(blob.size.toLong())
            if (ref < 0) out.bytes(blob) else ByteDelta.encode(blob, blobs[ref], out)
            first.putIfAbsent(groups[i], i)
            last[groups[i]] = i
        }
    }
}

internal class BlobKey(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is BlobKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}
