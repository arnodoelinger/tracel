package com.tracel.storage

import com.tracel.model.id.RollbackJobId
import com.tracel.storage.journal.SqliteJournal
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SqliteJournalTest {
    @Test
    fun `a step is completed only after markCompleted`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val journal = SqliteJournal(db.exposed)
            val job = RollbackJobId(1)

            assertFalse(journal.isCompleted(job, 0))
            journal.markCompleted(job, 0)
            assertTrue(journal.isCompleted(job, 0))
        }
    }

    @Test
    fun `marking the same step complete twice is not an error`(@TempDir dir: Path) {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val journal = SqliteJournal(db.exposed)
            val job = RollbackJobId(1)

            journal.markCompleted(job, 3)
            journal.markCompleted(job, 3)

            assertTrue(journal.isCompleted(job, 3))
        }
    }

    @Test
    fun `progress survives closing and reopening the same file`(@TempDir dir: Path) {
        val path = dir.resolve("db.sqlite")
        val job = RollbackJobId(42)

        TracelDatabase.open(path).use { db -> SqliteJournal(db.exposed).markCompleted(job, 2) }

        TracelDatabase.open(path).use { db ->
            val journal = SqliteJournal(db.exposed)
            assertTrue(journal.isCompleted(job, 2))
            assertFalse(journal.isCompleted(job, 3), "only what was actually marked should read back as completed")
        }
    }
}
