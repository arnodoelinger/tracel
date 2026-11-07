package com.tracel.storage.ffm

import com.tracel.storage.lsm.io.Disk
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.nio.file.Path

/**
 * A read-only mmap of a finished file, kept alive by its own [Arena].
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
        fun openRead(path: Path, disk: Disk): MappedFile {
            val arena = Arena.ofShared()
            return try {
                MappedFile(path, disk.map(path, arena), arena)
            } catch (e: Throwable) {
                arena.close()
                throw e
            }
        }
    }
}
