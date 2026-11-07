package com.tracel.storage.chrono

import com.tracel.engine.event.EventLog as EventLogPort
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.PurgeFilter
import com.tracel.engine.store.PurgeSpec
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.log.Seq
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.storage.ports.event.EventLog
import com.tracel.storage.ports.ops.purgeSome
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

class EventsLaneTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))
    private val steve = player(1)
    private val alex = player(2)
    private val millis0 = 1_700_000_000_000L

    private fun events(n: Int): List<ActorEvent> {
        val random = Random(5)
        var millis = millis0
        val kinds = EventKind.entries
        return (1..n).map { i ->
            millis += random.nextLong(0, 4000)
            val placed = random.nextInt(3) != 0
            ActorEvent(
                Seq(i.toLong()), kinds[random.nextInt(kinds.size)], if (random.nextInt(6) == 0) null else if (random.nextBoolean()) steve else alex,
                millis, if (placed) BlockPos(if (random.nextInt(5) == 0) nether else overworld, random.nextInt(-40, 40), 64, random.nextInt(-40, 40)) else null,
                if (random.nextBoolean()) "message $i" else null,
            )
        }
    }

    private fun admits(f: LookupFilter, kinds: Set<EventKind>, e: ActorEvent): Boolean {
        if (e.kind !in kinds || !f.admitsTime(e.epochMillis)) return false
        if (f.holders.isNotEmpty() && e.by !in f.holders) return false
        if (e.by != null && e.by in f.excludedHolders) return false
        val world = f.region?.world ?: f.world ?: return true
        val at = e.at ?: return false
        return at.world == world && (f.region?.contains(at) ?: true)
    }

    private suspend fun assertAll(log: EventLogPort, written: List<ActorEvent>, label: String) {
        val since = millis0 + (written.maxOf { it.epochMillis } - millis0) / 2
        val kinds = EventKind.entries.toSet()
        val filters = listOf(
            LookupFilter(limit = Int.MAX_VALUE), LookupFilter(limit = 40, offset = 7), LookupFilter(since = since, limit = Int.MAX_VALUE),
            LookupFilter(holders = setOf(steve), limit = Int.MAX_VALUE), LookupFilter(excludedHolders = setOf(steve), limit = Int.MAX_VALUE),
            LookupFilter(world = nether, limit = Int.MAX_VALUE),
            LookupFilter(region = LookupRegion(overworld, -1, 1, -1, 1), limit = Int.MAX_VALUE),
            LookupFilter(region = LookupRegion(overworld, -3, 2, -3, 2, minX = -10, maxX = 10, minY = 0, maxY = 100, minZ = -10, maxZ = 10), since = since, limit = Int.MAX_VALUE),
        )
        for ((i, f) in filters.withIndex()) {
            val want = written.filter { admits(f, kinds, it) }.sortedWith(compareByDescending<ActorEvent> { it.epochMillis }.thenByDescending { it.seq.raw })
                .drop(f.offset).take(f.limit).map { it.copy(entity = null) }
            assertEquals(want, log.query(f, kinds), "$label: filter $i")
        }
        val one = setOf(written.first().kind)
        assertEquals(written.count { it.kind in one }, log.query(LookupFilter(limit = Int.MAX_VALUE), one).size, "$label: one kind")
    }

    @Test
    fun `events answer every filter the way a list would, merged or not, and after a reopen`(@TempDir dir: Path) = runTest {
        val written = events(1200)
        Stack(dir).use { stack ->
            val log = EventLog(stack.storage)
            for (e in written.take(700)) log.append(e)
            assertAll(log, written.take(700), "fragments")
            Chrono.of(stack.storage).mergeAll()
            for (e in written.drop(700)) log.append(e)
            assertAll(log, written, "merged then more")
        }
        Stack(dir).use { stack -> assertAll(EventLog(stack.storage), written, "reopened") }
    }

    @Test
    fun `a purge of events takes events and leaves the world's changes alone`(@TempDir dir: Path) = runTest {
        val written = events(600)
        Stack(dir).use { stack ->
            val log = EventLog(stack.storage)
            for (e in written) log.append(e)
            Chrono.of(stack.storage).mergeAll()
            val report = purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.EVENTS), PurgeFilter(player = steve.uuid)))
            assertEquals(written.count { it.by == steve }.toLong(), report.matched)
            assertAll(log, written.filter { it.by != steve }, "after purging steve")
        }
    }
}
