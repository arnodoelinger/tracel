package com.tracel.storage.ports.ops

import com.github.luben.zstd.ZstdInputStream
import com.github.luben.zstd.ZstdOutputStream
import com.tracel.storage.TracelStorage
import com.tracel.storage.ffm.Bytes.readBytes
import com.tracel.storage.spi.MutationBatch
import com.tracel.storage.util.eachRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val MAGIC = 0x54455850 // TEXP
private const val VERSION = 1
private const val BATCH_ROWS = 20_000

/** What an export turned out to be, for the line that gets printed afterward. */
data class ExportSummary(val rows: Long, val bytes: Long, val file: Path)

/** The whole history as one file you can carry. */
suspend fun exportTo(storage: TracelStorage, to: Path): ExportSummary = withContext(Dispatchers.IO) {
    val temporary = to.resolveSibling("${to.fileName}.writing")
    Files.deleteIfExists(temporary)
    Files.createDirectories(to.toAbsolutePath().parent)

    var rows = 0L
    DataOutputStream(
        BufferedOutputStream(ZstdOutputStream(Files.newOutputStream(temporary), 5), 1 shl 16),
    ).use { out ->
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        rows = storage.read {
            var written = 0L
            eachRow(ByteArray(0)) { cursor ->
                val key = cursor.key()
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
    ExportSummary(rows, Files.size(to), to)
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

    val rows = ArrayList<Pair<ByteArray, ByteArray>>()
    var stated = -1L
    DataInputStream(
        BufferedInputStream(ZstdInputStream(Files.newInputStream(from)), 1 shl 16),
    ).use { input ->
        require(input.readInt() == MAGIC) { "$from is not a Tracel export" }
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
            rows += key to value
        }
    }
    require(stated == rows.size.toLong()) {
        "$from says it holds $stated rows and holds ${rows.size} — it was truncated"
    }

    storage.engine.wipe()
    var written = 0
    while (written < rows.size) {
        val batch = MutationBatch()
        val until = minOf(written + BATCH_ROWS, rows.size)
        for (i in written until until) batch.put(rows[i].first, rows[i].second)
        storage.engine.write(batch, durable = false)
        written = until
    }
    storage.engine.sync()
    ExportSummary(rows.size.toLong(), Files.size(from), from)
}
