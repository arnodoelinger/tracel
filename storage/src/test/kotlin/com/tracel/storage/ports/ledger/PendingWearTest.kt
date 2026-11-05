package com.tracel.storage.ports.ledger

import com.tracel.model.item.ItemKey
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.diamond
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class PendingWearTest {
    private val pickaxe = ItemKey("minecraft:diamond_pickaxe")

    @Test
    fun `kept stacks survive a restart and come back in order, once`(@TempDir dir: Path) = runTest {
        val steve = UUID(0, 1)
        val first = byteArrayOf(1, 2, 3)
        val second = ByteArray(5_000) { it.toByte() }
        Stack(dir).use { stack ->
            PendingWear(stack.storage, stack.counters).keepAll(steve, mapOf(pickaxe to listOf(first, second)))
        }
        Stack(dir).use { stack ->
            val wear = PendingWear(stack.storage, stack.counters)
            val claimed = wear.claimFor(steve)

            assertEquals(setOf(pickaxe), claimed.keys)
            assertEquals(2, claimed.getValue(pickaxe).size)
            assertArrayEquals(first, claimed.getValue(pickaxe)[0])
            assertArrayEquals(second, claimed.getValue(pickaxe)[1])
            assertEquals(emptyMap<ItemKey, List<ByteArray>>(), wear.claimFor(steve), "a claim empties it")
        }
    }

    @Test
    fun `a stack kept later comes after, keys and players apart`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val wear = PendingWear(stack.storage, stack.counters)
            val (steve, alex) = UUID(0, 1) to UUID(0, 2)
            wear.keepAll(steve, mapOf(pickaxe to listOf(byteArrayOf(1))))
            wear.keepAll(steve, mapOf(pickaxe to listOf(byteArrayOf(2)), diamond to listOf(byteArrayOf(3))))
            wear.keepAll(alex, mapOf(pickaxe to listOf(byteArrayOf(4))))

            val claimed = wear.claimFor(steve)
            assertEquals(listOf(1, 2), claimed.getValue(pickaxe).map { it.single().toInt() })
            assertEquals(listOf(3), claimed.getValue(diamond).map { it.single().toInt() })
            assertEquals(listOf(4), wear.claimFor(alex).getValue(pickaxe).map { it.single().toInt() })
        }
    }

    @Test
    fun `a stack kept in the same unit as its delivery rolls back with it`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val wear = PendingWear(stack.storage, stack.counters)
            val steve = UUID(0, 1)
            runCatching {
                stack.storage.atomically {
                    wear.keepAll(steve, mapOf(pickaxe to listOf(byteArrayOf(1))))
                    error("the delivery write failed")
                }
            }

            assertEquals(emptyMap<ItemKey, List<ByteArray>>(), wear.claimFor(steve), "no wear without its delivery")
        }
    }
}
