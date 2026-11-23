package com.tracel.storage.chrono.lith

import java.lang.foreign.MemorySegment

/** [Voids] as a capsule carries them. */
internal object VoidCodec {
    private const val EG = Coder.EG
    private const val SG = Coder.SIGNED
    private const val COUNT = 0
    private const val MILLIS = COUNT + EG
    private const val CUBE = MILLIS + SG
    private const val CELL = CUBE + SG
    private const val SLOTS = CELL + 32 * 3

    fun encode(voids: Voids, startMillis: Long): ByteArray {
        val c = Encoder(SLOTS)
        code(c, voids, startMillis)
        return c.finish()
    }

    fun decode(seg: MemorySegment, at: Long, end: Long, startMillis: Long): Voids =
        code(Decoder(SLOTS, seg, at, end), null, startMillis)

    private fun code(c: Coder, voids: Voids?, startMillis: Long): Voids {
        val maps = voids?.maps?.sortedWith(compareBy({ it.millis }, { it.cubeY })).orEmpty()
        val n = c.gamma(COUNT, maps.size.toLong()).toInt()
        val out = ArrayList<VoidMap>(n)
        val lastByCube = HashMap<Int, LongArray>()
        val byMoment = HashMap<Int, LongArray>()
        var millis = startMillis
        var cube = 0
        var moment = Long.MIN_VALUE
        for (m in 0 until n) {
            val want = if (c.decoding) null else maps[m]
            millis += c.signed(MILLIS, if (want == null) 0 else want.millis - millis)
            if (millis != moment) {
                moment = millis
                byMoment.clear()
            }
            cube += c.signed(CUBE, if (want == null) 0L else (want.cubeY - cube).toLong()).toInt()
            val earlier = lastByCube[cube]
            val below = byMoment[cube - 1]
            val bits = LongArray(64)
            fun at(b: LongArray, i: Int): Int = ((b[i ushr 6] ushr (i and 63)) and 1L).toInt()
            for (i in 0 until 4096) {
                val x = i and 15
                val z = (i shr 4) and 15
                val y = i shr 8
                val l = if (x > 0) at(bits, i - 1) else 0
                val b = if (z > 0) at(bits, i - 16) else 0
                val d = if (y > 0) at(bits, i - 256) else if (below != null) at(below, i + 3840) else 0
                val lb = if (x > 0 && z > 0) at(bits, i - 17) else 0
                val edge = if (x == 0 || z == 0 || (y == 0 && below == null)) 1 else 0
                val prior = if (earlier == null) 0 else 1 + at(earlier, i)
                val ctx = (l or (b shl 1) or (d shl 2) or (lb shl 3) or (edge shl 4)) * 3 + prior
                val bit = c.bit(CELL + ctx, if (want == null) 0 else at(want.bits, i))
                if (bit == 1) bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
            }
            lastByCube[cube] = bits
            byMoment[cube] = bits
            out += VoidMap(millis, cube, bits)
        }
        return Voids(out)
    }
}
