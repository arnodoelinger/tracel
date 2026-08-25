package com.tracel.storage.ffm

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A read-only `mmap` of a finished file, kept alive by its own [Arena].
 *
 * Segments are immutable once published, so mapping them shared and never unmapping until
 * close is the whole story: a lookup reads straight out of the page cache.
 */
class MappedFile private constructor(
    val path: Path,
    val segment: MemorySegment,
    private val arena: Arena,
) : AutoCloseable {
    val size: Long get() = segment.byteSize()

    override fun close() {
        arena.close()
    }

    companion object {
        fun openRead(path: Path): MappedFile {
            val arena = Arena.ofShared()
            return try {
                FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                    val segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size(), arena)
                    MappedFile(path, segment, arena)
                }
            } catch (e: Throwable) {
                arena.close()
                throw e
            }
        }
    }
}

fun fsyncDirectory(directory: Path) {
    FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
}
