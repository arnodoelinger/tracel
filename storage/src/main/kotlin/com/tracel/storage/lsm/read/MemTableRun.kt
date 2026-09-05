package com.tracel.storage.lsm.read

import com.tracel.storage.lsm.InternalKey
import com.tracel.storage.lsm.write.MemTable

internal class MemTableRun(private val table: MemTable) : Run() {
    private var node = 0L

    override fun seek(internalKey: ByteArray, length: Int) {
        node = table.seek(internalKey, length)
        refresh()
    }

    override fun next() {
        node = table.nextOf(node, 0)
        refresh()
    }

    override fun sequence(): Long = table.sequenceOf(node)
    override fun isDeletion(): Boolean = table.isDeletion(node)
    override fun value() = table.valueOf(node)
    override fun userKeyBytes(): ByteArray = table.userKeyBytes(node)

    private fun refresh() {
        val at = node
        if (at == 0L) {
            valid = false
            return
        }
        val header = table.header(at)
        valid = true
        keySegment = table.segment
        keyOffset = table.keyOffsetOf(at, header)
        keyLength = table.keyLengthOf(header)
        userKeyLength = keyLength - InternalKey.TRAILER_BYTES
    }
}
