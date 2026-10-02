package com.tracel.storage.lsm.state

import com.tracel.storage.ffm.Bytes
import com.tracel.storage.ffm.fsyncDirectory
import com.tracel.storage.lsm.segment.SegmentMeta
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The one file that says what the database is: which segments are live, which write-ahead
 * logs still need replaying, and how far the sequence counter got.
 *
 * Publishing a new one is the atomic commit point for a flush or a compaction. Write a temp
 * file, `fsync` it, rename it over the old one, `fsync` the directory. `POSIX` rename is atomic,
 * so a reader — or a restart, for example — sees exactly one of the two manifests and never a
 * torn mixture.
 *
 * Every failure between those steps leaves the previous manifest in force, which is why a
 * compaction that dies halfway costs nothing but the orphaned files [Manifest.sweep] removes.
 *
 * @param lastSequence the last sequence number that was written to disk, so a replay knows where to start
 * @param nextFileId the next file id to use for a new segment or write-a
 * @param walIds the list of write-ahead log ids that need to be replayed to recover the database
 * @param segments the list of segment metadata that are currently live in the database
 *
 * @return the manifest, or [EMPTY] if it doesn't exist
 */
data class Manifest(
    val lastSequence: Long,
    val nextFileId: Long,
    val walIds: List<Long>,
    val segments: List<SegmentMeta>,
) {
    /** Writes this manifest to disk, atomically, of course, replacing any previous manifest. */
    fun write(directory: Path) {
        var size = MAGIC.size + 4 + 8 + 8 + 4 + walIds.size * 8 + 4
        for ((_, _, _, _, firstKey, lastKey) in segments) {
            size += 8 + 4 + 4 + 8 + 4 + firstKey.size + 4 + lastKey.size + 4 + 8
        }
        size += 4

        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putInt(VERSION)
        buffer.putLong(lastSequence)
        buffer.putLong(nextFileId)
        buffer.putInt(walIds.size)
        walIds.forEach(buffer::putLong)
        buffer.putInt(segments.size)
        for (meta in segments) {
            buffer.putLong(meta.id)
            buffer.putInt(meta.level)
            buffer.putInt(meta.entries)
            buffer.putLong(meta.fileBytes)
            buffer.putInt(meta.firstKey.size)
            buffer.put(meta.firstKey)
            buffer.putInt(meta.lastKey.size)
            buffer.put(meta.lastKey)
            buffer.putInt(meta.category)
            buffer.putLong(meta.window)
        }
        val body = buffer.array().copyOf(buffer.position())
        buffer.putInt(Bytes.checksum(body))

        val temp = directory.resolve("$NAME.tmp")
        java.io.FileOutputStream(temp.toFile()).use { out ->
            out.write(buffer.array())
            out.flush()
            out.fd.sync()
        }
        Files.move(temp, directory.resolve(NAME), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        fsyncDirectory(directory)
    }

    companion object {
        const val NAME = "tracel.index"
        const val SEGMENTS = "segments"
        const val SEGMENT_SUFFIX = ".seg"
        const val LOG_SUFFIX = ".log"

        private val MAGIC = "TMAN".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1

        val EMPTY = Manifest(lastSequence = 0, nextFileId = 1, walIds = listOf(1), segments = emptyList())

        /**
         * Reads the manifest from disk.
         *
         * @return the manifest, or [EMPTY] if it doesn't exist
         */
        fun read(directory: Path): Manifest {
            val path = directory.resolve(NAME)
            if (!Files.exists(path)) return EMPTY
            val bytes = Files.readAllBytes(path)
            require(bytes.size >= 12) { "$path is truncated" }

            val stated = ByteBuffer.wrap(bytes, bytes.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(Bytes.checksum(bytes.copyOf(bytes.size - 4)) == stated) {
                "$path failed its checksum — the manifest was never the file that got torn, so this is disk corruption"
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(ByteArray(4).also(buffer::get).contentEquals(MAGIC)) { "$path is not a Tracel manifest" }
            val version = buffer.int
            require(version == VERSION) { "$path is manifest v$version, this build reads v$VERSION" }

            val lastSequence = buffer.long
            val nextFileId = buffer.long
            val walIds = List(buffer.int) { buffer.long }
            val segments = List(buffer.int) {
                val id = buffer.long
                val level = buffer.int
                val entries = buffer.int
                val fileBytes = buffer.long
                val firstKey = ByteArray(buffer.int).also { key -> buffer.get(key) }
                val lastKey = ByteArray(buffer.int).also { key -> buffer.get(key) }
                val category = buffer.int
                val window = buffer.long
                SegmentMeta(id, level, entries, fileBytes, firstKey, lastKey, category, window)
            }
            return Manifest(lastSequence, nextFileId, walIds, segments)
        }

        /** @return the path to the segments directory, creating it if necessary. */
        fun segmentsDirectory(directory: Path): Path = directory.resolve(SEGMENTS)

        /** @return the path to a segment file with the given id. */
        fun segmentPath(directory: Path, id: Long): Path =
            segmentsDirectory(directory).resolve("%08d%s".format(id, SEGMENT_SUFFIX))

        /** @return the path to a write-ahead log file with the given id. */
        fun walPath(directory: Path, id: Long): Path =
            directory.resolve("%08d%s".format(id, LOG_SUFFIX))

        /** Removes any files in the directory that are not referenced by the manifest. */
        fun sweep(directory: Path, manifest: Manifest, writing: Set<Long> = emptySet()) {
            val liveWals = manifest.walIds.mapTo(HashSet()) { walPath(directory, it).fileName }
            Files.newDirectoryStream(directory).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (name.endsWith(LOG_SUFFIX) && path.fileName !in liveWals) Files.deleteIfExists(path)
                    if (name == "$NAME.tmp") Files.deleteIfExists(path)
                }
            }

            val segments = segmentsDirectory(directory)
            if (!Files.isDirectory(segments)) return
            val live = manifest.segments.mapTo(HashSet()) { segmentPath(directory, it.id).fileName }
            for (id in writing) live.add(segmentPath(directory, id).fileName)
            Files.newDirectoryStream(segments).use { stream ->
                for (path in stream) {
                    if (path.fileName.toString().endsWith(SEGMENT_SUFFIX) && path.fileName !in live) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
