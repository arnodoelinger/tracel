package com.tracel.storage.lsm.write

import com.tracel.storage.ffm.Bytes
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The write-ahead log: the only file whose contents decide what survives losing the machine.
 *
 * Frame layout, little-endian:
 * ```
 * +0     u32    payload length
 * +4     u32    CRC32C of the payload
 * +8     i64    batch sequence
 * +16    ..     payload: repeated [u32 keyLen][i32 valueLen, -1 = delete][key][value]
 * ```
 */
class Wal private constructor(
    val path: Path,
    private val stream: FileOutputStream,
    private var written: Long,
) : AutoCloseable {
    private val header: ByteBuffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var payload: ByteBuffer = ByteBuffer.allocate(INITIAL_PAYLOAD).order(ByteOrder.LITTLE_ENDIAN)
    private var unsynced = false

    val bytes: Long get() = written

    /** Appends a batch to the log, with its sequence. */
    fun append(sequence: Long, batch: com.tracel.storage.spi.MutationBatch) {
        payload.clear()
        batch.forEach { key, value ->
            ensure(8 + key.size + (value?.size ?: 0))
            payload.putInt(key.size)
            payload.putInt(value?.size ?: -1)
            payload.put(key)
            if (value != null) payload.put(value)
        }
        payload.flip()

        val length = payload.remaining()
        val crc = java.util.zip.CRC32C().apply { update(payload.duplicate()) }.value.toInt()

        header.clear()
        header.putInt(length)
        header.putInt(crc)
        header.putLong(sequence)
        header.flip()

        stream.write(header.array(), 0, HEADER_BYTES)
        stream.write(payload.array(), 0, length)
        written += HEADER_BYTES + length
        unsynced = true
    }

    /** Forces what has been written to the platter. */
    fun sync() {
        if (!unsynced) return
        stream.fd.sync()
        unsynced = false
        syncCount++
    }

    private var syncCount = 0L

    val syncs: Long get() = syncCount

    override fun close() {
        sync()
        stream.close()
    }

    private fun ensure(additional: Int) {
        if (payload.remaining() >= additional) return
        val grown = ByteBuffer.allocate((payload.capacity() * 2).coerceAtLeast(payload.position() + additional))
            .order(ByteOrder.LITTLE_ENDIAN)
        payload.flip()
        grown.put(payload)
        payload = grown
    }

    companion object {
        private const val HEADER_BYTES = 16
        private const val INITIAL_PAYLOAD = 1 shl 16

        /** Creates a new [Wal] for [path], appending to it if it already exists. */
        fun create(path: Path): Wal { // Don't change this to FileChannel
            val existing = if (Files.exists(path)) Files.size(path) else 0L
            return Wal(path, FileOutputStream(path.toFile(), true), existing)
        }

        /**
         * Replays every intact frame of [path] with a sequence above [afterSequence].
         *
         * Stops — quietly, on purpose — at the first frame that is truncated or fails its
         * checksum. That frame is where the machine died mid-write, and everything after it is
         * by definition not part of any durable prefix.
         *
         * @return the highest sequence applied.
         */
        fun replay(path: Path, afterSequence: Long, apply: (Long, ByteArray, ByteArray?) -> Unit): Long {
            if (!Files.exists(path)) return afterSequence
            var highest = afterSequence
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                var at = 0L
                val head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                while (at + HEADER_BYTES <= size) {
                    head.clear()
                    if (readFully(channel, head, at) < HEADER_BYTES) break
                    head.flip()
                    val length = head.int
                    val crc = head.int
                    val sequence = head.long
                    if (length < 0 || at + HEADER_BYTES + length > size) break

                    val body = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(channel, body, at + HEADER_BYTES) < length) break
                    body.flip()
                    if (Bytes.checksum(body.array()) != crc) break

                    if (sequence > afterSequence) {
                        while (body.hasRemaining()) {
                            val keyLength = body.int
                            val valueLength = body.int
                            val key = ByteArray(keyLength).also { body.get(it) }
                            val value = if (valueLength < 0) null else ByteArray(valueLength).also { body.get(it) }
                            apply(sequence, key, value)
                        }
                        if (sequence > highest) highest = sequence
                    }
                    at += HEADER_BYTES + length
                }
            }
            return highest
        }

        private fun readFully(channel: FileChannel, buffer: ByteBuffer, at: Long): Int {
            var position = at
            var total = 0
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read <= 0) break
                position += read
                total += read
            }
            return total
        }
    }
}

/** How often the write-ahead log is forced to stable storage. */
sealed interface SyncPolicy {
    data object EveryBatch : SyncPolicy
    data class Interval(val millis: Long) : SyncPolicy
    data object Never : SyncPolicy
}
