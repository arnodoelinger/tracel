package com.tracel.storage.lsm.write

import com.tracel.storage.ffm.Bytes
import com.tracel.storage.lsm.disk.Disk
import com.tracel.storage.spi.MutationBatch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.util.zip.CRC32C

/** What [Wal.replay] found: the highest sequence it applied, and the damage it stopped at, if any. */
class Replayed(val highest: Long, val damage: Damage?)

/**
 * Where a log stopped being readable.
 *
 * A [tear] is what a power cut usually leaves: past [at] there is no intact frame, only a cut-short one, zeros
 * or junk. When an intact frame does follow, either pages landed out of order or bytes went bad on the disk.
 */
class Damage(val at: Long, val size: Long, val tear: Boolean)

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
    private val sink: Disk.Sink,
    private var written: Long,
    private val beforeSync: () -> Unit,
) : AutoCloseable {
    private val header: ByteBuffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private var payload: ByteBuffer = ByteBuffer.allocate(INITIAL_PAYLOAD).order(ByteOrder.LITTLE_ENDIAN)
    private var unsynced = false

    var broken: IOException? = null; private set

    val bytes: Long get() = written

    /** Appends a batch to the log, with its sequence. */
    fun append(sequence: Long, batch: MutationBatch) {
        broken?.let { throw IOException("$path is broken since an earlier fsync failed.", it) }
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
        val crc = CRC32C().apply { update(payload.duplicate()) }.value.toInt()

        header.clear()
        header.putInt(length)
        header.putInt(crc)
        header.putLong(sequence)
        header.flip()

        try {
            sink.write(header.array(), 0, HEADER_BYTES)
            sink.write(payload.array(), 0, length)
        } catch (e: IOException) {
            broken = e
            throw e
        }
        written += HEADER_BYTES + length
        unsynced = true
    }

    /** Forces what has been written to the platter. Once that failed, it fails every time after. */
    fun sync() {
        broken?.let { throw IOException("$path is broken since an earlier fsync failed.", it) }
        if (!unsynced) return
        try {
            beforeSync()
            sink.sync()
        } catch (e: IOException) {
            broken = e
            throw e
        }
        unsynced = false
        syncCount++
    }

    private var syncCount = 0L

    val syncs: Long get() = syncCount

    override fun close() {
        if (broken != null) return abandon()
        sync()
        sink.close()
    }

    /**
     * Closes without `fsync`: for a log whose contents are on disk elsewhere already,
     * or are lost anyway.
     */
    fun abandon() {
        try {
            sink.close()
        } catch (_: IOException) {
        }
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

        const val MIN_PAYLOAD = 8

        /** Creates a new [Wal] for [path], appending to it if it already exists. */
        fun create(path: Path, disk: Disk, beforeSync: () -> Unit = { }): Wal {
            val existing = disk.size(path).coerceAtLeast(0)
            return Wal(path, disk.write(path, fresh = false), existing, beforeSync)
        }

        /**
         * Replays every intact frame of [path] with a sequence above [afterSequence].
         *
         * Stops at the first frame that is truncated, fails its checksum or could never have been written, and
         * says what it found. In the newest log that is where the machine died mid-write; anywhere else it is
         * damage, and the caller decides what to do with the logs after it.
         *
         * A power cut does not leave a clean cut. The size can reach the disk before the data, so a log (!) may end in
         * zeros, and pages land in any order, so it (!) may end in a hole and then more frames.
         *
         * Zeros are actually why a frame shorter than [MIN_PAYLOAD], or numbered no higher than the one before it, is
         * damage: an all-zero header would pass its own checksum, `CRC32C` of nothing being `0`.
         *
         * @return the highest sequence applied, and the damage if the log did not end cleanly.
         */
        fun replay(path: Path, disk: Disk, afterSequence: Long, apply: (Long, ByteArray, ByteArray?) -> Unit): Replayed {
            if (!disk.exists(path)) return Replayed(afterSequence, null)
            var highest = afterSequence
            var damage: Damage? = null
            disk.read(path).use { source ->
                val size = source.size
                var at = 0L
                var previous = 0L
                val head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                while (at + HEADER_BYTES <= size) {
                    head.clear()
                    if (readFully(source, head, at) < HEADER_BYTES) break
                    head.flip()
                    val length = head.int
                    val crc = head.int
                    val sequence = head.long
                    if (length < MIN_PAYLOAD || sequence <= previous || at + HEADER_BYTES + length > size) break

                    val body = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
                    if (readFully(source, body, at + HEADER_BYTES) < length) break
                    body.flip()
                    if (Bytes.checksum(body.array()) != crc) break
                    previous = sequence

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
                if (at < size) damage = Damage(at, size, tear = !resumes(source, at + 1, size, previous))
            }
            return Replayed(highest, damage)
        }

        /** Whether an intact frame numbered above [after] starts anywhere in [from]..[size]. Damage only. */
        private fun resumes(source: Disk.Source, from: Long, size: Long, after: Long): Boolean {
            if (size - from < HEADER_BYTES + MIN_PAYLOAD) return false
            val rest = ByteBuffer.allocate(Math.toIntExact(size - from)).order(ByteOrder.LITTLE_ENDIAN)
            readFully(source, rest, from)
            val bytes = rest.array()
            val crc = CRC32C()
            for (at in 0..bytes.size - HEADER_BYTES - MIN_PAYLOAD) {
                val length = rest.getInt(at)
                if (length < MIN_PAYLOAD || length > bytes.size - at - HEADER_BYTES) continue
                if (rest.getLong(at + 8) <= after) continue
                crc.reset()
                crc.update(bytes, at + HEADER_BYTES, length)
                if (crc.value.toInt() == rest.getInt(at + 4)) return true
            }
            return false
        }

        private fun readFully(source: Disk.Source, buffer: ByteBuffer, at: Long): Int {
            var position = at
            var total = 0
            while (buffer.hasRemaining()) {
                val read = source.read(buffer, position)
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
