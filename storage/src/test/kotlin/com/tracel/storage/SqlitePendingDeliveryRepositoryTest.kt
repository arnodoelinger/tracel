package com.tracel.storage

import com.tracel.model.id.RollbackJobId
import com.tracel.storage.pending.SqlitePendingDeliveryRepository
import com.tracel.tests.support.Fixtures.diamond
import com.tracel.tests.support.Fixtures.diamondBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class SqlitePendingDeliveryRepositoryTest {
    @Test
    fun `claiming returns everything queued for that player, signed deltas intact`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqlitePendingDeliveryRepository(db.storage)
            val player = UUID(0L, 1)

            repo.enqueueAll(player, mapOf(diamond to 64L, diamondBlock to -1L), RollbackJobId(1), nowMillis = 1000L)

            val claimed = repo.claimFor(player)
            assertEquals(setOf(diamond to 64L, diamondBlock to -1L), claimed.map { it.itemKey to it.delta }.toSet())
        }
    }

    @Test
    fun `claiming removes the entries - a second claim for the same player finds nothing`(@TempDir dir: Path) = runTest {
        // This is the exact property that prevents the duplication bug found live for
        // rollback undo from recurring here: physical delivery must never be able to
        // re-read (and re-apply) the same queued entries twice.
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqlitePendingDeliveryRepository(db.storage)
            val player = UUID(0L, 1)

            repo.enqueueAll(player, mapOf(diamond to 64L), RollbackJobId(1), nowMillis = 1000L)

            assertEquals(1, repo.claimFor(player).size, "first claim sees the queued entry")
            assertTrue(repo.claimFor(player).isEmpty(), "second claim must find nothing left")
        }
    }

    @Test
    fun `claiming one player never touches another player's queue`(@TempDir dir: Path) = runTest {
        TracelDatabase.open(dir.resolve("db.sqlite")).use { db ->
            val repo = SqlitePendingDeliveryRepository(db.storage)
            val alice = UUID(0L, 1)
            val bob = UUID(0L, 2)

            repo.enqueueAll(alice, mapOf(diamond to 64L), RollbackJobId(1), nowMillis = 1000L)
            repo.enqueueAll(bob, mapOf(diamond to 32L), RollbackJobId(2), nowMillis = 1000L)

            val claimedForAlice = repo.claimFor(alice)
            assertEquals(1, claimedForAlice.size)
            assertEquals(64L, claimedForAlice.single().delta)

            val claimedForBob = repo.claimFor(bob)
            assertEquals(1, claimedForBob.size)
            assertEquals(32L, claimedForBob.single().delta)
        }
    }

    @Test
    fun `a queued delivery survives reopening the database`(@TempDir dir: Path) = runTest {
        val path = dir.resolve("db.sqlite")
        val player = UUID(0L, 1)

        TracelDatabase.open(path).use { db ->
            SqlitePendingDeliveryRepository(db.storage).enqueueAll(player, mapOf(diamond to 64L), RollbackJobId(1), nowMillis = 1000L)
        }

        TracelDatabase.open(path).use { db ->
            val claimed = SqlitePendingDeliveryRepository(db.storage).claimFor(player)
            assertEquals(1, claimed.size)
            assertEquals(diamond, claimed.single().itemKey)
            assertEquals(64L, claimed.single().delta)
        }
    }
}
