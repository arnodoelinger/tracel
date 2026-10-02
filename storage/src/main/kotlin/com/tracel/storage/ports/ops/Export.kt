package com.tracel.storage.ports.ops

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.KeyReader
import com.tracel.storage.codec.Keys
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.util.eachRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val MAGIC = "TEXP".toByteArray(Charsets.US_ASCII)
private const val VERSION = 1
private const val BATCH_ROWS = 20_000
internal const val TIME_KEY_SIZE = 17

/** What an export turned out to be, for the line that gets printed afterward. */
data class ExportSummary(
    val rows: Long,
    val bytes: Long,
    val file: Path,
    val oldest: Long? = null,
    val newest: Long? = null,
)

/** The whole history as one file you can carry. */
suspend fun exportTo(storage: TracelStorage, to: Path): ExportSummary = withContext(Dispatchers.IO) {
    val temporary = to.resolveSibling("${to.fileName}.writing")
    Files.deleteIfExists(temporary)
    Files.createDirectories(to.toAbsolutePath().parent)

    var rows = 0L
    var oldest = Long.MAX_VALUE
    var newest = Long.MIN_VALUE
    DataOutputStream(
        BufferedOutputStream(ZstdOutputStream(Files.newOutputStream(temporary), 5), 1 shl 16),
    ).use { out ->
        out.write(MAGIC)
        out.writeInt(VERSION)
        rows = storage.read {
            var written = 0L
            eachRow(ByteArray(0)) { cursor ->
                val key = cursor.key()
                if (key.size == TIME_KEY_SIZE && key[0] == Keys.TIME) {
                    val at = Keys.invert(KeyReader.u64(key, 1))
                    if (at < oldest) oldest = at
                    if (at > newest) newest = at
                }
                val value = cursor.value()
                val bytes = value.readBytes(0, value.byteSize().toInt())
                out.writeInt(key.size)
                out.write(key)
                out.writeInt(bytes.size)
                out.write(bytes)
                written++
            }
            written
        }
        out.writeInt(-1)
        out.writeLong(rows)
    }

    Files.move(temporary, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    ExportSummary(rows, Files.size(to), to, oldest.takeIf { rows > 0 && it != Long.MAX_VALUE }, newest.takeIf { it != Long.MIN_VALUE })
}

/**
 * Replaces everything in [storage] with what [from] holds.
 *
 * Destructive. Importing a history beside another one would interleave two sets of
 * sequence numbers that were never meant to meet. The store is wiped first, so what
 * comes out is the file and nothing else.
 */
suspend fun importFrom(storage: TracelStorage, from: Path): ExportSummary = withContext(Dispatchers.IO) {
    require(Files.exists(from)) { "$from does not exist" }

    // Checked whole before anything is wiped, then read again straight into the store
    val rows = eachExportedRow(from) { _, _ -> }

    // Nothing else writes while the store is swapped, and the IDs cached for the old one go with it:
    // a new holder given an ID the file already uses overwrote history.
    storage.alone {
        storage.engine.wipe()
        var batch = MutationBatch()
        var inBatch = 0
        eachExportedRow(from) { key, value ->
            batch.put(key, value)
            if (++inBatch >= BATCH_ROWS) {
                storage.engine.write(batch, durable = false)
                batch = MutationBatch()
                inBatch = 0
            }
        }
        if (inBatch > 0) storage.engine.write(batch, durable = false)
        storage.engine.sync()
        storage.reloadInterning()
    }
    ExportSummary(rows, Files.size(from), from)
}

/** Streams every row of the export at [from] through [row], and checks the count it ends on. */
private inline fun eachExportedRow(from: Path, row: (ByteArray, ByteArray) -> Unit): Long {
    var rows = 0L
    var stated = -1L
    DataInputStream(
        BufferedInputStream(ZstdInputStream(Files.newInputStream(from)), 1 shl 16),
    ).use { input ->
        require(ByteArray(MAGIC.size).also(input::readFully).contentEquals(MAGIC)) { "$from is not a Tracel export" }
        val version = input.readInt()
        require(version == VERSION) { "$from is export v$version, this build reads v$VERSION" }
        while (true) {
            val keyLength = try {
                input.readInt()
            } catch (end: EOFException) {
                throw IllegalArgumentException("$from ends without saying how many rows it held", end)
            }
            if (keyLength == -1) {
                stated = input.readLong()
                break
            }
            val key = ByteArray(keyLength).also(input::readFully)
            val value = ByteArray(input.readInt()).also(input::readFully)
            row(key, value)
            rows++
        }
    }
    require(stated == rows) { "$from says it holds $stated rows and holds $rows — it was truncated" }
    return rows
}
