package com.tracel.storage.ports.actor

import com.tracel.engine.actor.EntityKindSource
import com.tracel.model.holder.HolderId
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.ports.ops.purgeAll
import com.tracel.storage.support.Stack
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*

class ActorFactsTest {
    private val steve = UUID(0L, 1L)
    private val zombie = UUID(0L, 2L)

    @Test
    fun `a player's mode is the one they switched to last before the moment asked`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val facts = ActorFacts(stack.storage)
            facts.noteMode(steve, 0, 1_000)
            facts.noteMode(steve, 1, 2_000)

            val timeline = facts.modesOf(listOf(steve)).getValue(steve)

            assertNull(timeline.at(999), "before anyone knew")
            assertEquals(0, timeline.at(1_999))
            assertEquals(1, timeline.at(2_000))
            assertEquals(1, timeline.at(9_000_000))
        }
    }

    @Test
    fun `noting the mode a player is already in changes nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val facts = ActorFacts(stack.storage)
            facts.noteMode(steve, 1, 1_000)
            facts.noteMode(steve, 1, 5_000)

            val timeline = facts.modesOf(listOf(steve)).getValue(steve)
            assertEquals(1, timeline.at(1_000))
            assertEquals(1, timeline.at(5_000))
        }
    }

    @Test
    fun `a player nothing was noted about is left out`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            assertEquals(emptyMap<UUID, Any>(), ActorFacts(stack.storage).modesOf(listOf(steve)))
        }
    }

    @Test
    fun `a mob's type is written down the first time it gets an id`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.interning.entityKinds =
                EntityKindSource { EntityTypeKey("minecraft:zombie").takeIf { _ -> it == zombie } }

            stack.storage.write { stack.storage.interning.internHolder(this, HolderId.Entity(zombie)) }

            assertEquals(
                mapOf(zombie to EntityTypeKey("minecraft:zombie")),
                ActorFacts(stack.storage).kindsOf(listOf(zombie, steve)),
            )
        }
    }

    @Test
    fun `a mob the server cannot name is left out rather than guessed`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.interning.entityKinds = EntityKindSource { null }

            stack.storage.write { stack.storage.interning.internHolder(this, HolderId.Entity(zombie)) }

            assertEquals(emptyMap<UUID, EntityTypeKey>(), ActorFacts(stack.storage).kindsOf(listOf(zombie)))
        }
    }

    @Test
    fun `a visit names itself while it is open and for a moment after`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val facts = ActorFacts(stack.storage)
            facts.openVisit(steve, 1_000)
            facts.closeVisit(steve, 5_000)
            facts.openVisit(steve, 9_000)

            val visits = facts.visitsOf(listOf(steve)).getValue(steve)

            assertNull(visits.at(999), "before the first")
            assertEquals(1_000L, visits.at(3_000))
            assertEquals(1_000L, visits.at(5_900), "the click that lands just after the close")
            assertNull(visits.at(7_000), "between two visits")
            assertEquals(9_000L, visits.at(1_000_000), "still open")
        }
    }

    @Test
    fun `closing when nothing is open does nothing`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val facts = ActorFacts(stack.storage)
            facts.closeVisit(steve, 1_000)
            facts.openVisit(steve, 2_000)
            facts.closeVisit(steve, 3_000)
            facts.closeVisit(steve, 9_000)

            assertNull(facts.visitsOf(listOf(steve)).getValue(steve).at(5_000))
        }
    }

    @Test
    fun `modes, types and visits survive the store being reopened`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.interning.entityKinds = EntityKindSource { EntityTypeKey("minecraft:creeper") }
            stack.storage.write { stack.storage.interning.internHolder(this, HolderId.Entity(zombie)) }
            val facts = ActorFacts(stack.storage)
            facts.noteMode(steve, 3, 10)
            facts.openVisit(steve, 20)
        }
        Stack(dir).use { stack ->
            val facts = ActorFacts(stack.storage)
            assertEquals(EntityTypeKey("minecraft:creeper"), facts.kindsOf(listOf(zombie)).getValue(zombie))
            assertEquals(3, facts.modesOf(listOf(steve)).getValue(steve).at(10))
            assertEquals(20L, facts.visitsOf(listOf(steve)).getValue(steve).at(30))
        }
    }

    @Test
    fun `a purge forgets visits but keeps what a mob is and the mode a player is in`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            stack.storage.interning.entityKinds = EntityKindSource { EntityTypeKey("minecraft:husk") }
            stack.storage.write { stack.storage.interning.internHolder(this, HolderId.Entity(zombie)) }
            val facts = ActorFacts(stack.storage)
            facts.noteMode(steve, 2, 10)
            facts.openVisit(steve, 20)

            purgeAll(stack.storage)

            assertEquals(EntityTypeKey("minecraft:husk"), facts.kindsOf(listOf(zombie)).getValue(zombie))
            assertEquals(2, facts.modesOf(listOf(steve)).getValue(steve).at(10))
            assertEquals(emptyMap<UUID, Any>(), facts.visitsOf(listOf(steve)))
        }
    }
}
