package com.tracel.storage.ports.job

import com.tracel.engine.rollback.plan.*
import com.tracel.model.rollback.RollbackJobId
import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class JournalTest {
    @Test
    fun `journal progress survives a reopen and does not cross between rollback and undo`(@TempDir dir: Path) =
        runTest {
            val job = RollbackJobId(4)
            Stack(dir).use { stack ->
                stack.journal.markCompleted(job, 3)
                assertTrue(stack.journal.isCompleted(job, 3))
                assertFalse(stack.involutionJournal.isCompleted(job, 3), "undo progress is not rollback progress")
            }
            Stack(dir).use { stack ->
                assertTrue(
                    stack.journal.isCompleted(job, 3),
                    "a journal that forgets across a restart is not a journal"
                )
                assertFalse(stack.journal.isCompleted(job, 4))
            }
        }

    @Test
    fun `completed is the marked steps, not a probe of every index up to count`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val job = RollbackJobId(9)
            stack.journal.markCompleted(job, 0)
            stack.journal.markCompleted(job, 7)
            assertEquals(setOf(0, 7), stack.journal.completed(job, 10_000))
            assertEquals(emptySet<Int>(), stack.journal.completed(RollbackJobId(8), 10_000))
        }
    }

    @Test
    fun `marking the same step twice is a no-op`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.journal.markCompleted(RollbackJobId(1), 0)
            stack.journal.markCompleted(RollbackJobId(1), 0)
            assertTrue(stack.journal.isCompleted(RollbackJobId(1), 0))
        }
    }
}
