package com.tracel.plugin.command.scrub

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/** What the steps of a scrub changed. */
internal class StepLog : AutoCloseable {
    private val path = Files.createTempFile("tracel-scrub-", ".bin").also { it.toFile().deleteOnExit() }
    private val channel = FileChannel.open(path, READ, WRITE)
    private val pending = ByteBuffer.allocate(BATCH * ENTRY)

    /** How many entries there are, the ones still in the write buffer included. */
    var entries = 0L; private set

    /** Append a part. */
    fun append(pos: Long, before: Int, after: Int) {
        if (!pending.hasRemaining()) drain()
        pending.putLong(pos).putInt(before).putInt(after)
        entries++
    }

    /** Puts everything written so far where it can be read. */
    fun flush() = drain()

    /** Visits the entries from [from] up to [to]: from the first to the last, or backwards with [reverse]. */
    fun scan(from: Long, to: Long, reverse: Boolean, visit: (pos: Long, before: Int, after: Int) -> Unit) {
        val count = (to - from).toInt()
        if (count <= 0) return
        val buffer = ByteBuffer.allocate(count * ENTRY)
        var read = 0L
        while (buffer.hasRemaining()) {
            val got = channel.read(buffer, from * ENTRY + read)
            if (got < 0) break
            read += got
        }
        val range = if (reverse) (count - 1 downTo 0) else (0 until count)
        for (i in range) {
            val at = i * ENTRY
            visit(buffer.getLong(at), buffer.getInt(at + 8), buffer.getInt(at + 12))
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { Files.deleteIfExists(path) }
    }

    private fun drain() {
        pending.flip()
        var at = written
        while (pending.hasRemaining()) at += channel.write(pending, at)
        written = at
        pending.clear()
    }

    private var written = 0L

    private companion object {
        const val ENTRY = 16
        const val BATCH = 4096
    }
}
