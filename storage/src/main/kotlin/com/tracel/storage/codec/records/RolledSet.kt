package com.tracel.storage.codec.records

import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI64
import java.lang.foreign.MemorySegment

/**
 * Which sequence numbers of one bucket a rollback took back.
 *
 * ```
 * [i64 epochMillis] then either
 *   runs   -> [u12 first][u12 length - 1] ... three bytes a run
 *   bitmap -> 512 bytes, one bit a sequence number
 * ```
 *
 * A rollback takes records that sit next to each other in the log, so a bucket is a handful of runs. The bitmap is for
 * the bucket that is not, and its length is what tells the two apart: no number of runs is 512 bytes long.
 */
object RolledSet {
    const val SPAN = 4096

    private const val SHIFT = 12
    private const val WORDS = SPAN / 64
    private const val HEAD = 8
    private const val BITMAP_BYTES = SPAN / 8
    private const val RUN_BYTES = 3

    fun bucketOf(seq: Long): Long = seq ushr SHIFT

    fun slotOf(seq: Long): Int = (seq and (SPAN - 1L)).toInt()

    fun millis(value: MemorySegment): Long = value.i64(0)

    /** The set in [value] as bits, one word for every 64 slots; nothing for no value. */
    fun slots(value: MemorySegment?): LongArray {
        val words = LongArray(WORDS)
        if (value == null) return words
        val body = (value.byteSize() - HEAD).toInt()
        if (body == BITMAP_BYTES) {
            for (i in 0 until WORDS) words[i] = value.i64(HEAD + i * 8L)
            return words
        }
        var at = HEAD.toLong()
        repeat(body / RUN_BYTES) {
            val packed = u8(value, at) shl 16 or (u8(value, at + 1) shl 8) or u8(value, at + 2)
            val first = packed ushr SHIFT
            for (slot in first..first + (packed and (SPAN - 1))) add(words, slot)
            at += RUN_BYTES
        }
        return words
    }

    fun add(words: LongArray, slot: Int) {
        words[slot ushr 6] = words[slot ushr 6] or (1L shl (slot and 63))
    }

    fun has(words: LongArray, slot: Int): Boolean = words[slot ushr 6] and (1L shl (slot and 63)) != 0L

    /** [words] taken back at [millis], in whichever of the two forms is smaller. */
    fun encode(millis: Long, words: LongArray): ByteArray {
        var runs = 0
        var inside = false
        for (slot in 0 until SPAN) {
            val set = has(words, slot)
            if (set && !inside) runs++
            inside = set
        }
        if (runs * RUN_BYTES >= BITMAP_BYTES) {
            return recordBytes(HEAD + BITMAP_BYTES) {
                putI64(0, millis)
                for (i in 0 until WORDS) putI64(HEAD + i * 8L, words[i])
            }
        }

        val out = ByteArray(HEAD + runs * RUN_BYTES)
        MemorySegment.ofArray(out).putI64(0, millis)
        var at = HEAD
        var slot = 0
        while (slot < SPAN) {
            if (!has(words, slot)) {
                slot++
                continue
            }
            val first = slot
            while (slot < SPAN && has(words, slot)) slot++
            val packed = first shl SHIFT or (slot - first - 1)
            out[at++] = (packed ushr 16).toByte()
            out[at++] = (packed ushr 8).toByte()
            out[at++] = packed.toByte()
        }
        return out
    }

    private fun u8(value: MemorySegment, at: Long): Int = value.i8(at).toInt() and 0xFF
}
