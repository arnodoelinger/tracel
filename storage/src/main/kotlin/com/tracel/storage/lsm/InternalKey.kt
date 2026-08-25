package com.tracel.storage.lsm

/** `userKey || BE64((MAX_SEQ - sequence) << 8 | type)`. */
object InternalKey {
    const val TRAILER_BYTES = 8
    const val TYPE_DELETE: Byte = 0
    const val TYPE_VALUE: Byte = 1

    private const val MAX_SEQUENCE = (1L shl 56) - 1

    fun trailer(sequence: Long, type: Byte): Long {
        require(sequence in 0..MAX_SEQUENCE) { "sequence $sequence does not fit 56 bits" }
        return ((MAX_SEQUENCE - sequence) shl 8) or (type.toLong() and 0xFF)
    }

    fun sequenceOf(trailer: Long): Long = MAX_SEQUENCE - (trailer ushr 8)

    /** Appends the trailer to [userKey], big-endian, into a fresh array. */
    fun encode(userKey: ByteArray, sequence: Long, type: Byte): ByteArray {
        val out = ByteArray(userKey.size + TRAILER_BYTES)
        userKey.copyInto(out)
        val t = trailer(sequence, type)
        for (i in 0 until 8) out[userKey.size + i] = (t ushr (56 - i * 8)).toByte()
        return out
    }

    /** The lowest possible internal key for [userKey]. */
    fun seekTarget(userKey: ByteArray): ByteArray {
        val out = ByteArray(userKey.size + TRAILER_BYTES)
        userKey.copyInto(out)
        return out
    }

    fun userKeyLength(internalLength: Int): Int = internalLength - TRAILER_BYTES
}
