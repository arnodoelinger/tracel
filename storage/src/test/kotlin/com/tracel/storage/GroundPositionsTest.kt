package com.tracel.storage

import com.tracel.storage.ports.world.GroundPositions
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.block
import com.tracel.tests.support.Fixtures.itemEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GroundPositionsTest {
    @Test
    fun `a position round-trips`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val positions = GroundPositions(stack.storage)
            val pile = itemEntity(1)

            positions.rememberAll(mapOf(pile to block(12, 64, -30)))

            assertEquals(block(12, 64, -30), positions.find(pile))
        }
    }

    @Test
    fun `a pile nothing ever saw answers null rather than guessing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            assertNull(GroundPositions(stack.storage).find(itemEntity(99)))
        }
    }

    @Test
    fun `a batch keeps each pile's own place, not the first one's`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val positions = GroundPositions(stack.storage)
            val batch = (1L..20L).associate { itemEntity(it) to block(it.toInt(), 64, 0) }

            positions.rememberAll(batch)

            for ((pile, at) in batch) assertEquals(at, positions.find(pile), "$pile")
        }
    }

    @Test
    fun `remembering the same pile again moves it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val positions = GroundPositions(stack.storage)
            val pile = itemEntity(2)

            positions.rememberAll(mapOf(pile to block(0, 64, 0)))
            positions.rememberAll(mapOf(pile to block(0, 64, 9)))

            assertEquals(block(0, 64, 9), positions.find(pile), "a pile that drifted before it died")
        }
    }

    @Test
    fun `positions survive the store being reopened`(@TempDir dir: Path) = runTest {
        val pile = itemEntity(3)
        Stack(dir).use { stack ->
            GroundPositions(stack.storage).rememberAll(mapOf(pile to block(7, 70, 7)))
        }
        Stack(dir).use { stack ->
            assertEquals(
                block(7, 70, 7),
                GroundPositions(stack.storage).find(pile),
                "the whole reason this is on disk and not in a map",
            )
        }
    }

    @Test
    fun `an empty batch writes nothing and does not fail`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            GroundPositions(stack.storage).rememberAll(emptyMap())
        }
    }
}
