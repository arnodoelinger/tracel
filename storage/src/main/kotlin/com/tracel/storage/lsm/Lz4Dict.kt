package com.tracel.storage.lsm

import java.util.Arrays

/**
 * LZ4 using a dictionary that lives once per segment.
 *
 * The dict is trained on this segment's own blocks plus a tiny seed of `Tracel` key tags
 * and packed record shapes — hopper moves, actor indexes, lots — so later blocks of the
 * same grief / session hit matches on the first bytes instead of re-learning the layout.
 */
internal object Lz4Dict {
    const val SIZE = 4096
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val HASH_LOG = 13
    private const val HASH_SIZE = 1 shl HASH_LOG

    /**
     * Keyspace tags and the 3-byte varint header a quiet index row actually writes.
     * Real blocks overwrite the rest of the dict; this is only the cold start.
     */
    private val SEED: ByteArray = byteArrayOf(
        0x01, 0x02, 0x03, 0x04, 0x05,
        0x06, 0x07, 0x08, 0x09, 0x0A,
        0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14,
        0x15, 0x16, 0x17, 0x18, 0x19,
        0x1A, 0x1B, 0x1C, 0x1D, 0x1E,
        0x1F, 0x00, 0x08, 0x01, 0x00,
        0x10, 0x01, 0x00, 0x08, 0x15,
        0x00, 0x08, 0x19,
    )

    private val HASH = ThreadLocal.withInitial { IntArray(HASH_SIZE) }

    fun train(blocks: List<ByteArray>): ByteArray {
        val out = ByteArray(SIZE) // Raw
        var at = 0
        fun add(src: ByteArray, max: Int) {
            if (at >= out.size || src.isEmpty()) return
            val n = minOf(max, src.size, out.size - at)
            System.arraycopy(src, 0, out, at, n)
            at += n
        }
        add(SEED, SEED.size) // Cold
        if (blocks.isEmpty()) return out.copyOf(at.coerceAtLeast(1))
        add(blocks[0], 1600)
        val mid = blocks.size / 2
        if (mid in 1 until blocks.lastIndex) add(blocks[mid], 900)
        add(blocks.last(), 900)
        var i = 1
        val step = (blocks.size / 6).coerceAtLeast(1)
        while (i < blocks.lastIndex && at < out.size) {
            add(blocks[i], 80)
            i += step
        }
        return out.copyOf(at.coerceAtLeast(1))
    }

    fun compress(dict: ByteArray, src: ByteArray, srcLen: Int, dest: ByteArray): Int {
        if (srcLen < MF_LIMIT + LAST_LITERALS) return -1
        val base = dict.size
        val end = base + srcLen
        val hash = HASH.get()
        Arrays.fill(hash, -1)
        var p = 0
        val dictHashEnd = base - MIN_MATCH + 1
        while (p < dictHashEnd) {
            hash[hash(dict, src, base, p)] = p
            p++
        }

        var ip = base
        var anchor = base
        var op = 0
        val ilimit = end - LAST_LITERALS
        val mflimit = end - MF_LIMIT

        while (ip < mflimit) {
            val h = hash(dict, src, base, ip)
            val ref = hash[h]
            hash[h] = ip
            val dist = if (ref >= 0) ip - ref else 0
            if (ref < 0 || dist == 0 || dist > 0xFFFF || readInt(dict, src, base, ref) != readInt(dict, src, base, ip)) {
                ip++
                continue
            }
            val match = MIN_MATCH + matchLen(dict, src, base, ref + MIN_MATCH, ip + MIN_MATCH, ilimit - ip - MIN_MATCH)
            val written = emit(dest, op, src, anchor - base, ip - anchor, dist, match)
            if (written < 0) return -1
            op = written
            ip += match
            anchor = ip
        }
        return emitLast(dest, op, src, anchor - base, end - anchor)
    }

    fun decompress(dict: ByteArray, src: ByteArray, srcLen: Int, dest: ByteArray, destLen: Int) {
        var ip = 0
        var op = 0
        while (ip < srcLen) {
            val token = src[ip].toInt() and 0xFF
            ip++
            var lit = token ushr 4
            if (lit == 15) {
                while (ip < srcLen) {
                    val b = src[ip].toInt() and 0xFF
                    ip++
                    lit += b
                    if (b != 255) break
                }
            }
            require(op + lit <= destLen && ip + lit <= srcLen) { "lz4 dict literals overrun" }
            System.arraycopy(src, ip, dest, op, lit)
            ip += lit
            op += lit
            if (ip == srcLen) {
                require(op == destLen) { "lz4 dict trailing literals left $op of $destLen" }
                return
            }
            require(ip + 2 <= srcLen) { "lz4 dict truncated offset" }
            val offset = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            require(offset > 0) { "lz4 dict offset 0" }
            var match = token and 15
            if (match == 15) {
                while (ip < srcLen) {
                    val b = src[ip].toInt() and 0xFF
                    ip++
                    match += b
                    if (b != 255) break
                }
            }
            match += MIN_MATCH
            require(op + match <= destLen) { "lz4 dict match overrun" }
            copyMatch(dict, dest, op, offset, match)
            op += match
        }
        require(op == destLen) { "lz4 dict ended at $op of $destLen" }
    }

    private fun emit(
        dest: ByteArray, op0: Int, src: ByteArray, litOff: Int, litLen: Int, dist: Int, match: Int,
    ): Int {
        var op = op0
        val tokenLit = if (litLen >= 15) 15 else litLen
        val matchExtra = match - MIN_MATCH
        val tokenMatch = if (matchExtra >= 15) 15 else matchExtra
        val need = 1 + extraLen(litLen) + litLen + 2 + extraLen(matchExtra)
        if (op + need > dest.size) return -1
        dest[op++] = ((tokenLit shl 4) or tokenMatch).toByte()
        op = writeExtra(dest, op, litLen)
        System.arraycopy(src, litOff, dest, op, litLen)
        op += litLen
        dest[op++] = dist.toByte()
        dest[op++] = (dist ushr 8).toByte()
        op = writeExtra(dest, op, matchExtra)
        return op
    }

    private fun emitLast(dest: ByteArray, op0: Int, src: ByteArray, litOff: Int, litLen: Int): Int {
        var op = op0
        val tokenLit = if (litLen >= 15) 15 else litLen
        val need = 1 + extraLen(litLen) + litLen
        if (op + need > dest.size) return -1
        dest[op++] = (tokenLit shl 4).toByte()
        op = writeExtra(dest, op, litLen)
        System.arraycopy(src, litOff, dest, op, litLen)
        return op + litLen
    }

    private fun extraLen(n: Int): Int {
        if (n < 15) return 0
        var left = n - 15
        var bytes = 1
        while (left >= 255) {
            bytes++
            left -= 255
        }
        return bytes
    }

    private fun writeExtra(dest: ByteArray, op0: Int, n: Int): Int {
        if (n < 15) return op0
        var left = n - 15
        var op = op0
        while (left >= 255) {
            dest[op++] = 0xFF.toByte()
            left -= 255
        }
        dest[op++] = left.toByte()
        return op
    }

    private fun copyMatch(dict: ByteArray, dest: ByteArray, op: Int, offset: Int, match: Int) {
        val from = op - offset
        if (from < 0) {
            val dictOff = dict.size + from
            val fromDict = minOf(-from, match)
            System.arraycopy(dict, dictOff, dest, op, fromDict)
            val rest = match - fromDict
            if (rest > 0) copyMatch(dict, dest, op + fromDict, offset, rest)
            return
        }
        if (offset >= match) {
            System.arraycopy(dest, from, dest, op, match)
            return
        }
        var i = 0
        if (offset >= 8) {
            while (i + 8 <= match) {
                dest[op + i] = dest[from + i]
                dest[op + i + 1] = dest[from + i + 1]
                dest[op + i + 2] = dest[from + i + 2]
                dest[op + i + 3] = dest[from + i + 3]
                dest[op + i + 4] = dest[from + i + 4]
                dest[op + i + 5] = dest[from + i + 5]
                dest[op + i + 6] = dest[from + i + 6]
                dest[op + i + 7] = dest[from + i + 7]
                i += 8
            }
        }
        while (i < match) {
            dest[op + i] = dest[from + i]
            i++
        }
    }

    private fun matchLen(dict: ByteArray, src: ByteArray, base: Int, ref: Int, ip: Int, max: Int): Int {
        var n = 0
        while (n + 8 <= max && readLong(dict, src, base, ref + n) == readLong(dict, src, base, ip + n)) n += 8
        while (n < max && byteAt(dict, src, base, ref + n) == byteAt(dict, src, base, ip + n)) n++
        return n
    }

    private fun hash(dict: ByteArray, src: ByteArray, base: Int, pos: Int): Int =
        (readInt(dict, src, base, pos) * -1640531535) ushr (32 - HASH_LOG)

    private fun readInt(dict: ByteArray, src: ByteArray, base: Int, pos: Int): Int {
        val sp = pos - base
        if (sp >= 0) return leInt(src, sp)
        if (pos + 4 <= base) return leInt(dict, pos)
        return (byteAt(dict, src, base, pos).toInt() and 0xFF) or
            ((byteAt(dict, src, base, pos + 1).toInt() and 0xFF) shl 8) or
            ((byteAt(dict, src, base, pos + 2).toInt() and 0xFF) shl 16) or
            ((byteAt(dict, src, base, pos + 3).toInt() and 0xFF) shl 24)
    }

    private fun readLong(dict: ByteArray, src: ByteArray, base: Int, pos: Int): Long {
        val sp = pos - base
        if (sp >= 0) return leLong(src, sp)
        if (pos + 8 <= base) return leLong(dict, pos)
        var v = 0L
        var i = 0
        while (i < 8) {
            v = v or ((byteAt(dict, src, base, pos + i).toLong() and 0xFF) shl (i shl 3))
            i++
        }
        return v
    }

    private fun byteAt(dict: ByteArray, src: ByteArray, base: Int, pos: Int): Byte =
        if (pos >= base) src[pos - base] else dict[pos]

    private fun leInt(a: ByteArray, i: Int): Int =
        (a[i].toInt() and 0xFF) or
            ((a[i + 1].toInt() and 0xFF) shl 8) or
            ((a[i + 2].toInt() and 0xFF) shl 16) or
            ((a[i + 3].toInt() and 0xFF) shl 24)

    private fun leLong(a: ByteArray, i: Int): Long =
        (a[i].toLong() and 0xFF) or
            ((a[i + 1].toLong() and 0xFF) shl 8) or
            ((a[i + 2].toLong() and 0xFF) shl 16) or
            ((a[i + 3].toLong() and 0xFF) shl 24) or
            ((a[i + 4].toLong() and 0xFF) shl 32) or
            ((a[i + 5].toLong() and 0xFF) shl 40) or
            ((a[i + 6].toLong() and 0xFF) shl 48) or
            ((a[i + 7].toLong() and 0xFF) shl 56)
}
