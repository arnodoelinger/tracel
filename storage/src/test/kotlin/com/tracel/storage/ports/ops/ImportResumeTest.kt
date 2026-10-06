package com.tracel.storage.ports.ops

import com.tracel.storage.TracelStorage
import com.tracel.engine.store.ImportInterrupted
import com.tracel.storage.codec.Keys
import com.tracel.storage.spi.MutationBatch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ImportResumeTest {
    private fun row(n: Int) = byteArrayOf(0x7E, n.toByte())

    private suspend fun export(dir: Path): Path {
        val file = dir.resolve("export.tracel")
        TracelStorage.open(dir.resolve("source")).use { source ->
            source.write { for (n in 1..12) put(row(n), byteArrayOf(n.toByte())) }
            exportTo(source, file)
        }
        return file
    }

    private suspend fun cutShort(target: Path, file: Path) {
        TracelStorage.open(target).use { storage ->
            storage.write { put(row(99), byteArrayOf(9)) }
            val progress = ImportProgress(file.toAbsolutePath().toString(), Files.size(file), 12, 0)
            storage.engine.wipe(MutationBatch().apply { put(Keys.importProgress(), progress.encode()) })
            assertThrows<IllegalStateException> {
                copyImportRows(storage.engine, file, progress, startAt = 0, batchRows = 5) {
                    if (it >= 5) error("killed")
                }
            }
        }
    }

    @Test
    fun `an import cut short is finished from the file at the next start`(@TempDir dir: Path) = runTest {
        val file = export(dir)
        val target = dir.resolve("target")
        cutShort(target, file)

        TracelStorage.open(target).use { storage ->
            assertEquals(5L, InterruptedImport.pending(storage)?.done, "the record says how far it got")
            assertNotNull(InterruptedImport.resume(storage))

            assertNull(InterruptedImport.pending(storage), "and is gone once the last row is in")
            for (n in 1..12) assertTrue(storage.read { exists(row(n)) }, "row $n is in")
            assertFalse(storage.read { exists(row(99)) }, "what was there before the import is not")
        }
    }

    @Test
    fun `a database that is not in the middle of an import is left alone`(@TempDir dir: Path) {
        TracelStorage.open(dir).use { storage ->
            assertNull(InterruptedImport.resume(storage))
        }
    }

    @Test
    fun `an import whose file is gone is refused rather than left half done`(@TempDir dir: Path) = runTest {
        val file = export(dir)
        val target = dir.resolve("target")
        cutShort(target, file)
        Files.delete(file)

        TracelStorage.open(target).use { storage ->
            assertThrows<ImportInterrupted> { InterruptedImport.resume(storage) }
        }
    }
}
