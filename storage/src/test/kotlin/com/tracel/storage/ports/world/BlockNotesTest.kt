package com.tracel.storage.ports.world

import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.world
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class BlockNotesTest {
    @Test
    fun `notes survive the store being reopened`(@TempDir dir: Path) = runTest {
        val lectern = BlockPos(world, -12, -64, 30_000_000)
        Stack(dir).use { stack -> BlockNotes(stack.storage).writeAll(mapOf(lectern to 7)) }
        Stack(dir).use { stack ->
            assertEquals(mapOf(lectern to 7), BlockNotes(stack.storage).loadAll(), "the restart this exists for")
        }
    }

    @Test
    fun `a null note forgets, a new one overwrites`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val notes = BlockNotes(stack.storage)
            val kept = BlockPos(world, 1, 64, 1)
            val gone = BlockPos(world, 2, 64, 2)
            notes.writeAll(mapOf(kept to 3, gone to 4))
            notes.writeAll(mapOf(kept to 5, gone to null))

            assertEquals(mapOf(kept to 5), notes.loadAll())
        }
    }

    @Test
    fun `forgetting in a world nothing was written in writes nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val notes = BlockNotes(stack.storage)
            notes.writeAll(mapOf(BlockPos(WorldId(UUID(9L, 9L)), 0, 0, 0) to null))

            assertEquals(emptyMap<BlockPos, Int>(), notes.loadAll())
        }
    }

    @Test
    fun `same coordinates in two worlds are two notes`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val notes = BlockNotes(stack.storage)
            val here = BlockPos(world, 0, 64, 0)
            val nether = BlockPos(WorldId(UUID(0L, 2L)), 0, 64, 0)
            notes.writeAll(mapOf(here to 1, nether to 2))

            assertEquals(mapOf(here to 1, nether to 2), notes.loadAll())
        }
    }
}
