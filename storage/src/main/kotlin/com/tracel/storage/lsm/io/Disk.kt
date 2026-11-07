package com.tracel.storage.lsm.io

import java.io.OutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.file.Path

/** Every byte the engine puts on the disk or takes off. */
interface Disk {
    /** Opens [path] for appending, creating it if missing, or starting it over empty when [fresh]. */
    fun write(path: Path, fresh: Boolean): Sink

    /** Opens [path] for positional reads. */
    fun read(path: Path): Source

    /** Maps all of [path], read-only, for as long as [arena] lives. */
    fun map(path: Path, arena: Arena): MemorySegment

    /** The size of [path] in bytes, or `-1` when there is no such file. */
    fun size(path: Path): Long

    /** What is in [directory], or nothing when it does not exist. */
    fun list(directory: Path): List<Path>

    /** Creates [directory] and whatever parents it is missing. */
    fun createDirectories(directory: Path)

    /** Removes [path], if it is there. */
    fun delete(path: Path)

    /**
     * Renames [from] over [to] in one step: anyone looking sees the old [to] or the new one,
     * never neither.
     */
    fun replace(from: Path, to: Path)

    /** Makes the names in [directory] survive a power cut: files created, renamed or deleted there. */
    fun syncDirectory(directory: Path)

    /** Returns true if [path] exists. */
    fun exists(path: Path): Boolean = size(path) >= 0

    /** All of [path], in one array. Small files only, which the manifest is. */
    fun readAll(path: Path): ByteArray = read(path).use { source ->
        val bytes = ByteArray(Math.toIntExact(source.size))
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (source.read(buffer, buffer.position().toLong()) <= 0) break
        }
        bytes.copyOf(buffer.position())
    }

    /** One file being appended to. */
    interface Sink : AutoCloseable {
        fun write(bytes: ByteArray, offset: Int, length: Int)

        /** Forces everything written so far to stable storage. */
        fun sync()

        /** Closes without syncing. Closing twice is ok. */
        override fun close()

        /**
         * The same sink as an [OutputStream], for the writers that want to buffer.
         *
         * Closing it closes the sink.
         */
        fun stream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) = this@Sink.write(b, off, len)

            override fun close() = this@Sink.close()
        }
    }

    /** One file being read. */
    interface Source : AutoCloseable {
        val size: Long

        /** Reads into [buffer] from [at] on. */
        fun read(buffer: ByteBuffer, at: Long): Int

        override fun close()
    }
}
