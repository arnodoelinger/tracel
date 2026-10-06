package com.tracel.storage.format

import com.tracel.storage.TracelStorage
import com.tracel.engine.store.StoreFormatException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class StoreFormatTest {
    private val v10 = FormatVersion(1, 0)
    private val v11 = FormatVersion(1, 1)
    private val v12 = FormatVersion(1, 2)
    private fun marker(n: Int) = byteArrayOf(0x7E, n.toByte())

    private fun step(from: FormatVersion, to: FormatVersion, mark: Int) =
        Migration(from, to) { unit -> unit.put(marker(mark), byteArrayOf(1)) }

    @Test
    fun `a new database starts at the newest format and is left alone after`(@TempDir dir: Path) {
        TracelStorage.open(dir).use { storage ->
            val first = StoreFormat.ensure(storage, v12, emptyList())
            assertEquals(v12, first.to)
            assertFalse(first.migrated)
            assertFalse(StoreFormat.ensure(storage, v12, emptyList()).migrated)
        }
    }

    @Test
    fun `an older database is migrated one step at a time`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            StoreFormat.ensure(storage, v10, emptyList())
            val outcome = StoreFormat.ensure(storage, v12, listOf(step(v11, v12, 2), step(v10, v11, 1)))
            assertEquals(v10, outcome.from)
            assertEquals(v12, outcome.to)
            assertTrue(storage.read { exists(marker(1)) && exists(marker(2)) }, "both steps ran")
            assertFalse(StoreFormat.ensure(storage, v12, emptyList()).migrated, "and are not run again")
        }
    }

    @Test
    fun `a database with history and no version is the legacy format`(@TempDir dir: Path) = runTest {
        TracelStorage.open(dir).use { storage ->
            storage.write { put(byteArrayOf(0x7E, 99), byteArrayOf(1)) }
            val outcome = StoreFormat.ensure(storage, v11, listOf(step(v10, v11, 1)))
            assertEquals(StoreFormat.LEGACY, outcome.from)
            assertEquals(v11, outcome.to)
        }
    }

    @Test
    fun `a database from a newer minor version is refused`(@TempDir dir: Path) {
        TracelStorage.open(dir).use { storage ->
            StoreFormat.ensure(storage, v11, emptyList())
            assertThrows<StoreFormatException> { StoreFormat.ensure(storage, v10, emptyList()) }
        }
    }

    @Test
    fun `a database from another major version is refused`(@TempDir dir: Path) {
        TracelStorage.open(dir).use { storage ->
            StoreFormat.ensure(storage, FormatVersion(2, 0), emptyList())
            assertThrows<StoreFormatException> { StoreFormat.ensure(storage, v10, emptyList()) }
        }
    }

    @Test
    fun `a missing step is said, not skipped`(@TempDir dir: Path) {
        TracelStorage.open(dir).use { storage ->
            StoreFormat.ensure(storage, v10, emptyList())
            assertThrows<StoreFormatException> { StoreFormat.ensure(storage, v12, listOf(step(v11, v12, 2))) }
        }
    }
}
