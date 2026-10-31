package com.tracel.storage.codec

/** Number helpers and small codes that live beside the keys. */
object Keys {
    const val PROGRESS_ROLLBACK: Byte = 0
    const val PROGRESS_INVOLUTION: Byte = 1
    const val NS_ITEM_KEY: Byte = 0
    const val NS_HOLDER: Byte = 1
    const val NS_WORLD: Byte = 2
    const val NS_BLOCK_DATA: Byte = 3
    const val NS_ENTITY_TYPE: Byte = 4

    fun invert(sequence: Long): Long = Long.MAX_VALUE - sequence

    fun ordered(value: Int): Int = value xor Int.MIN_VALUE

    fun yBin(y: Int): Byte = (((y shr 4) + 64) and 0xFF).toByte()
}

// region Key writer & reader

/** Fixed-size big-endian key builder. Sized exactly, so it never grows and never copies. */
class KeyWriter(size: Int) {
    private val bytes = ByteArray(size)
    private var at = 0

    fun tag(value: Byte): KeyWriter = u8(value)

    fun u8(value: Byte): KeyWriter {
        bytes[at++] = value
        return this
    }

    fun u32(value: Int): KeyWriter {
        bytes[at++] = (value ushr 24).toByte()
        bytes[at++] = (value ushr 16).toByte()
        bytes[at++] = (value ushr 8).toByte()
        bytes[at++] = value.toByte()
        return this
    }

    fun u64(value: Long): KeyWriter {
        for (shift in 56 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte()
        return this
    }

    fun raw(value: ByteArray): KeyWriter {
        value.copyInto(bytes, at)
        at += value.size
        return this
    }

    fun done(): ByteArray {
        check(at == bytes.size) { "key writer sized ${bytes.size} but wrote $at bytes." }
        return bytes
    }
}

/** Reads back what [KeyWriter] wrote. */
object KeyReader {
    fun u32(key: ByteArray, at: Int): Int =
        ((key[at].toInt() and 0xFF) shl 24) or ((key[at + 1].toInt() and 0xFF) shl 16) or
                ((key[at + 2].toInt() and 0xFF) shl 8) or (key[at + 3].toInt() and 0xFF)

    fun u64(key: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (key[at + i].toLong() and 0xFF)
        return value
    }
}

// endregion
