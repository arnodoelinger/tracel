package com.tracel.storage.lsm

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class Lz4DictTest {
    @Test
    fun `a hopper-like block round-trips through the segment dictionary`() {
        val block = ByteArray(3000)
        var at = 0
        repeat(80) { i ->
            block[at++] = 0x00
            block[at++] = 0x08
            block[at++] = 0x15
            val seq = i.toLong()
            repeat(8) { b -> block[at++] = (seq ushr (56 - b * 8)).toByte() }
            repeat(20) { block[at++] = 0 }
        }
        val raw = block.copyOf(at)
        val dict = Lz4Dict.train(listOf(raw, raw))
        val dest = ByteArray(raw.size * 2)
        val n = Lz4Dict.compress(dict, raw, raw.size, dest)
        assertTrue(n > 0 && n < raw.size, "dict compress should shrink hopper rows, got $n of ${raw.size}")
        val out = ByteArray(raw.size)
        Lz4Dict.decompress(dict, dest.copyOf(n), n, out, raw.size)
        assertArrayEquals(raw, out)
    }

    @Test
    fun `random bytes still round-trip`() {
        val raw = Random(1).nextBytes(2048)
        roundTrip(Lz4Dict.train(listOf(raw)), raw)
    }

    @Test
    fun `overlapping matches of zeros round-trip`() {
        val raw = ByteArray(2500)
        roundTrip(Lz4Dict.train(listOf(raw)), raw)
    }

    @Test
    fun `a block that only matches the dictionary round-trips`() {
        val dict = ByteArray(64) { it.toByte() }
        val raw = ByteArray(2000) { dict[it % dict.size] }
        roundTrip(dict, raw)
    }

    private fun roundTrip(dict: ByteArray, raw: ByteArray) {
        val dest = ByteArray(raw.size * 2 + 64)
        val n = Lz4Dict.compress(dict, raw, raw.size, dest)
        assertTrue(n > 0, "compress failed")
        val out = ByteArray(raw.size)
        Lz4Dict.decompress(dict, dest.copyOf(n), n, out, raw.size)
        assertArrayEquals(raw, out)
    }
}
