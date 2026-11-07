package com.tracel.storage.lsm.io

import java.io.FileOutputStream
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*

/** Local disk implementation of [Disk], using the filesystem and memory-mapped files. */
object LocalDisk : Disk {
    // Warning: only FileOutputStream here
    override fun write(path: Path, fresh: Boolean): Disk.Sink = Appender(FileOutputStream(path.toFile(), !fresh))

    override fun read(path: Path): Disk.Source = Reader(FileChannel.open(path, StandardOpenOption.READ))

    override fun map(path: Path, arena: Arena): MemorySegment =
        FileChannel.open(path, StandardOpenOption.READ)
            .use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size(), arena) }

    override fun size(path: Path): Long = try {
        Files.size(path)
    } catch (_: NoSuchFileException) {
        -1
    }

    override fun list(directory: Path): List<Path> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.newDirectoryStream(directory).use { it.toList() }
    }

    override fun createDirectories(directory: Path) {
        Files.createDirectories(directory)
    }

    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun replace(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun syncDirectory(directory: Path) {
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private class Appender(private val out: FileOutputStream) : Disk.Sink {
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        override fun sync() = out.fd.sync()

        override fun close() = out.close()
    }

    private class Reader(private val channel: FileChannel) : Disk.Source {
        override val size: Long get() = channel.size()

        override fun read(buffer: ByteBuffer, at: Long): Int = channel.read(buffer, at)

        override fun close() = channel.close()
    }
}
