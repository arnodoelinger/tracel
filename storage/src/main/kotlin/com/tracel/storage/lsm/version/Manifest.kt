package com.tracel.storage.lsm.version

import com.tracel.platform.Versions
import com.tracel.storage.ffm.Bytes
import com.tracel.storage.lsm.io.Disk
import com.tracel.storage.lsm.table.SegmentMetadata
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

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
    val segments: List<SegmentMetadata>,
) {
    /** Writes this manifest to disk, atomically, of course, replacing any previous manifest. */
    fun write(directory: Path, disk: Disk) {
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
        disk.write(temp, fresh = true).use { out ->
            out.write(buffer.array(), 0, size)
            out.sync()
        }
        disk.replace(temp, directory.resolve(NAME))
        disk.syncDirectory(directory)
    }

    companion object {
        const val NAME = "tracel.index"
        const val SEGMENTS = "segments"
        const val SEGMENT_SUFFIX = ".seg"
        const val LOG_SUFFIX = ".log"

        private val MAGIC = "TMAN".toByteArray(Charsets.US_ASCII)
        private const val VERSION = Versions.Format.MANIFEST

        val EMPTY = Manifest(lastSequence = 0, nextFileId = 1, walIds = listOf(1), segments = emptyList())

        /**
         * Reads the manifest from disk.
         *
         * @return the manifest, or [EMPTY] if it doesn't exist
         */
        fun read(directory: Path, disk: Disk): Manifest {
            val path = directory.resolve(NAME)
            if (!disk.exists(path)) return EMPTY
            val bytes = disk.readAll(path)
            require(bytes.size >= 12) { "$path is truncated." }

            val stated = ByteBuffer.wrap(bytes, bytes.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(Bytes.checksum(bytes.copyOf(bytes.size - 4)) == stated) {
                "$path failed its checksum; the manifest was never the file that got torn, so this is disk corruption."
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(ByteArray(4).also(buffer::get).contentEquals(MAGIC)) { "$path is not a Tracel manifest." }
            val version = buffer.int
            require(version == VERSION) { "$path is manifest v$version, this build reads v$VERSION." }

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
                SegmentMetadata(id, level, entries, fileBytes, firstKey, lastKey, category, window)
            }
            return Manifest(lastSequence, nextFileId, walIds, segments)
        }

        /**
         * Refuses a directory that holds data but no manifest.
         *
         * A missing manifest reads as [EMPTY], and [sweep] then deletes every segment and log it does not list.
         *
         * That is right for a new database... but a disaster for one whose index went missing.
         */
        fun requireNotLost(directory: Path, disk: Disk) {
            if (disk.exists(directory.resolve(NAME))) return
            val first = walPath(directory, EMPTY.walIds.single()).fileName
            val orphans = ArrayList<String>()
            for (path in disk.list(directory)) {
                val name = path.fileName
                if (name.toString().endsWith(LOG_SUFFIX) && name != first) orphans += name.toString()
            }
            for (path in disk.list(segmentsDirectory(directory))) {
                if (path.fileName.toString().endsWith(SEGMENT_SUFFIX)) orphans += path.fileName.toString()
            }
            check(orphans.isEmpty()) {
                "$directory has ${orphans.size} data files (${orphans.take(3).joinToString()}...) but no $NAME. " +
                        "Restore the manifest from a backup; starting would delete all of them."
            }
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
        fun sweep(directory: Path, disk: Disk, manifest: Manifest, writing: Set<Long> = emptySet()) {
            val liveWals = manifest.walIds.mapTo(HashSet()) { walPath(directory, it).fileName }
            for (path in disk.list(directory)) {
                val name = path.fileName.toString()
                if (name.endsWith(LOG_SUFFIX) && path.fileName !in liveWals) disk.delete(path)
                if (name == "$NAME.tmp") disk.delete(path)
            }

            val live = manifest.segments.mapTo(HashSet()) { segmentPath(directory, it.id).fileName }
            for (id in writing) live.add(segmentPath(directory, id).fileName)
            for (path in disk.list(segmentsDirectory(directory))) {
                if (path.fileName.toString().endsWith(SEGMENT_SUFFIX) && path.fileName !in live) disk.delete(path)
            }
        }
    }
}
