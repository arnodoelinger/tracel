package com.tracel.storage.ports.ops

import com.tracel.storage.StorageUnit
import com.tracel.engine.store.ImportInterrupted
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.Keys
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

internal const val IMPORT_BATCH_ROWS = 20_000

/**
 * Where an import that has not finished had got to: the export it reads, how big that file is, how many rows it holds
 * and how many of them are already in.
 *
 * The record lives in the database and lands in every batch of rows it counts, so it is never ahead of them and never
 * behind: a process killed at any moment resumes from exactly the rows that are there.
 */
data class ImportProgress(val file: String, val size: Long, val rows: Long, val done: Long) {
    /** Encodes the [ImportProgress] instance into a [ByteArray]. */
    internal fun encode(): ByteArray {
        val name = file.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(4 + name.size + 24).putInt(name.size).put(name).putLong(size).putLong(rows).putLong(done)
            .array()
    }

    internal companion object {
        /** Decodes a [ByteArray] into an [ImportProgress] instance. */
        fun decode(bytes: ByteArray): ImportProgress {
            val buffer = ByteBuffer.wrap(bytes)
            val name = ByteArray(buffer.int).also(buffer::get)
            return ImportProgress(String(name, Charsets.UTF_8), buffer.long, buffer.long, buffer.long)
        }
    }
}

/**
 * Finishing an import that a crash cut short.
 *
 * An import wipes the database and then copies the export in. The wipe and the record of the import land together, and
 * so does each batch of rows with the count of them, so the export file is always enough to finish what was started.
 */
object InterruptedImport {
    /** The import this database is in the middle of, or `null` if it is not. */
    fun pending(storage: TracelStorage): ImportProgress? =
        StorageUnit(storage.engine.snapshot(), MutationBatch()).use { unit ->
            unit.get(Keys.importProgress())?.let { value ->
                ImportProgress.decode(ByteArray(value.byteSize().toInt()).also { out ->
                    java.lang.foreign.MemorySegment.ofArray(out).copyFrom(value)
                })
            }
        }

    /**
     * Copies what is left of the export into the database, if an import was cut short. Meant for start-up, before the
     * store has any other user.
     *
     * @return the import it finished, or `null` if there was none
     * @throws ImportInterrupted if the file the import read is gone or is not the same one
     */
    fun resume(storage: TracelStorage): ImportProgress? {
        val progress = pending(storage) ?: return null
        val file = Path.of(progress.file)
        if (!Files.isRegularFile(file) || Files.size(file) != progress.size) {
            throw ImportInterrupted(
                "An import of $file was cut short, and the file is gone or has changed, so it cannot be finished. " +
                        "Put it back where it was, or delete the database folder to start from nothing."
            )
        }
        copyImportRows(storage.engine, file, progress, startAt = progress.done)
        storage.reloadInterning()
        return progress
    }
}

/**
 * Writes the rows of [from] after the first [startAt] into [engine], in batches that each carry how far they got, and
 * ends with a batch that takes the record away: the import is done when that lands, and not before.
 *
 * [afterBatch] is told how many rows are in after each batch. Tests stop an import there.
 */
internal fun copyImportRows(
    engine: KeyValueEngine,
    from: Path,
    progress: ImportProgress,
    startAt: Long,
    batchRows: Int = IMPORT_BATCH_ROWS,
    afterBatch: (Long) -> Unit = {},
) {
    val marker = Keys.importProgress()
    var batch = MutationBatch()
    var inBatch = 0
    var read = 0L
    eachExportedRow(from) { key, value ->
        read++
        if (read <= startAt) return@eachExportedRow
        if (key.contentEquals(marker)) return@eachExportedRow
        batch.put(key, value)
        if (++inBatch >= batchRows) {
            batch.put(marker, progress.copy(done = read).encode())
            engine.write(batch, durable = false)
            afterBatch(read)
            batch = MutationBatch()
            inBatch = 0
        }
    }
    batch.delete(marker)
    engine.write(batch, durable = true)
    engine.sync()
}

