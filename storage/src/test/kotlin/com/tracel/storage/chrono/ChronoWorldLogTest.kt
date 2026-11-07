package com.tracel.storage.chrono

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.PurgeFilter
import com.tracel.engine.store.PurgeSpec
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.admin.purgeSome
import com.tracel.storage.codec.Tag
import com.tracel.storage.support.Stack
import com.tracel.tests.support.Fixtures.player
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.random.Random

class ChronoWorldLogTest {
    private val overworld = WorldId(UUID(0L, 1L))
    private val nether = WorldId(UUID(0L, 2L))
    private val steve = player(1)
    private val alex = player(2)
    private val notch = player(3)
    private val air = shape("minecraft:air")
    private val shapes = listOf("minecraft:stone", "minecraft:oak_planks", "minecraft:glass", "minecraft:wheat[age=0]", "minecraft:wheat[age=7]").map(::shape)
    private val millis0 = 1_700_000_000_000L

    private fun shape(data: String) = BlockShape(BlockDataKey(data))

    private inner class Writer(private val stack: Stack, seed: Int) {
        private val random = Random(seed)
        private val state = HashMap<BlockPos, BlockShape>()
        val written = ArrayList<WorldChange>()
        var millis = millis0

        private fun now(pos: BlockPos): BlockShape = state[pos] ?: if (pos.y < 64) shapes[0] else air

        suspend fun write(n: Int) {
            repeat(n) {
                millis += random.nextLong(0, 3000)
                when (random.nextInt(20)) {
                    in 0..11 -> single()
                    12, 13, 14 -> blast()
                    15 -> sign()
                    16, 17 -> entity()
                    else -> rollbackOne()
                }
            }
        }

        private fun pos(): BlockPos {
            val world = if (random.nextInt(8) == 0) nether else overworld
            return BlockPos(world, random.nextInt(-40, 40), 60 + random.nextInt(0, 10), random.nextInt(-40, 40))
        }

        private fun who(): HolderId? = when (random.nextInt(5)) {
            0 -> null
            1 -> alex
            2 -> notch
            else -> steve
        }

        private suspend fun put(change: WorldChange) {
            stack.worldLog.append(change)
            written += change
        }

        private suspend fun single() {
            val at = pos()
            val before = now(at)
            val after = if (before == air) shapes[random.nextInt(shapes.size)] else air
            val action = if (after == air) ActionKind.BLOCK_BREAK else ActionKind.BLOCK_PLACE
            val cause = if (random.nextInt(7) == 0) CauseKind.NATURAL else CauseKind.PLAYER_ACTION
            put(WorldChange(stack.counters.nextSeq(), action, cause, who(), millis, at, ChangeSubject.Block(before, after)))
            state[at] = after
        }

        private suspend fun rollbackOne() {
            val at = pos()
            val before = now(at)
            put(WorldChange(stack.counters.nextSeq(), ActionKind.BLOCK_CHANGE, CauseKind.ROLLBACK, null, millis, at, ChangeSubject.Block(before, air)))
            state[at] = air
        }

        private suspend fun blast() {
            val center = pos()
            val edits = ArrayList<BlockEdit>()
            for (dx in -2..2) for (dz in -2..2) for (dy in -1..1) {
                val at = BlockPos(center.world, center.x + dx, center.y + dy, center.z + dz)
                edits += BlockEdit(at, now(at), air)
            }
            val by = who()
            var base = -1L
            val written = stack.worldLog.appendAll(BlockEdits(ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, by, millis, edits)) { n ->
                stack.counters.nextSeqRange(n).also { base = it.raw }
            }
            val real = edits.filter { it.before != it.after }
            assertEquals(real.size, written)
            for ((i, e) in real.withIndex()) {
                this.written += WorldChange(Seq(base + i), ActionKind.BLOCK_BREAK, CauseKind.EXPLOSION, by, millis, e.at, ChangeSubject.Block(e.before, e.after))
                state[e.at] = air
            }
        }

        private suspend fun sign() {
            val at = BlockPos(overworld, 3, 65, 3)
            val before = now(at)
            val after = BlockShape(BlockDataKey("minecraft:oak_sign"), BlockExtras.Opaque("line ${random.nextInt(99)}".toByteArray()))
            put(WorldChange(stack.counters.nextSeq(), ActionKind.SIGN_EDIT, CauseKind.PLAYER_ACTION, steve, millis, at, ChangeSubject.Block(before, after)))
            state[at] = after
        }

        private suspend fun entity() {
            val at = pos()
            val type = EntityTypeKey(if (random.nextBoolean()) "minecraft:item" else "minecraft:zombie")
            val shape = EntityShape(type, at.x + 0.25, at.y.toDouble(), at.z + 0.75, random.nextInt(360 * 128) / 128f, 0f, EntityExtras.Opaque(byteArrayOf(1, 2, random.nextInt(9).toByte())))
            val spawn = random.nextBoolean()
            put(
                WorldChange(
                    stack.counters.nextSeq(), if (spawn) ActionKind.ENTITY_SPAWN else ActionKind.ENTITY_REMOVE,
                    if (spawn) CauseKind.NATURAL else CauseKind.PLAYER_ACTION, who(), millis, at,
                    ChangeSubject.Entity(UUID(random.nextLong(), random.nextLong()), type, if (spawn) null else shape, if (spawn) shape else null),
                ),
            )
        }
    }

    private fun admits(filter: LookupFilter, c: WorldChange): Boolean {
        if (c.cause.isBookkeeping) return false
        val causes = filter.worldCauses ?: filter.causes
        if (causes.isNotEmpty() && c.cause !in causes) return false
        if (c.cause in filter.excludedCauses) return false
        if (filter.actions.isNotEmpty() && c.action !in filter.actions) return false
        val by = c.causedBy
        if (filter.holders.isNotEmpty() && by !in filter.holders) return false
        if (by != null && by in filter.excludedHolders) return false
        if (!filter.admitsTime(c.epochMillis)) return false
        if (filter.world != null && c.at.world != filter.world) return false
        val region = filter.region
        return region == null || region.contains(c.at)
    }

    private fun filters(since: Long): List<LookupFilter> {
        val all = Int.MAX_VALUE
        return listOf(
            LookupFilter(limit = all),
            LookupFilter(limit = 50, offset = 10),
            LookupFilter(since = since, limit = all),
            LookupFilter(since = since, until = since + 600_000, limit = all),
            LookupFilter(holders = setOf(steve), limit = all),
            LookupFilter(holders = setOf(alex, notch), since = since, limit = all),
            LookupFilter(excludedHolders = setOf(steve), limit = all),
            LookupFilter(causes = setOf(CauseKind.EXPLOSION), limit = all),
            LookupFilter(excludedCauses = setOf(CauseKind.NATURAL), limit = all),
            LookupFilter(actions = setOf(ActionKind.BLOCK_PLACE, ActionKind.SIGN_EDIT, ActionKind.ENTITY_SPAWN), limit = all),
            LookupFilter(world = nether, limit = all),
            LookupFilter(region = LookupRegion(overworld, -1, 1, -1, 1), limit = all),
            LookupFilter(region = LookupRegion(overworld, -3, 2, -3, 2, minX = -10, maxX = 10, minY = 62, maxY = 66, minZ = -10, maxZ = 10), limit = all),
            LookupFilter(region = LookupRegion(overworld, -3, 2, -3, 2), holders = setOf(alex), since = since, limit = all),
            LookupFilter(region = LookupRegion(nether, -3, 2, -3, 2), limit = 30, offset = 5),
        )
    }

    private fun ends(changes: List<WorldChange>): Set<Long> = changes.groupBy { c ->
        when (val s = c.subject) {
            is ChangeSubject.Entity -> s.entity
            is ChangeSubject.Block -> c.at
        }
    }.values.flatMap { listOf(it.minBy { c -> c.seq.raw }, it.maxBy { c -> c.seq.raw }) }.map { it.seq.raw }.toSet()

    private suspend fun assertLog(stack: Stack, written: List<WorldChange>, label: String) {
        val newest = written.sortedByDescending { it.seq.raw }
        val since = millis0 + (written.maxOf { it.epochMillis } - millis0) / 2
        for ((i, filter) in filters(since).withIndex()) {
            val want = newest.filter { admits(filter, it) }.drop(filter.offset).take(filter.limit)
            assertEquals(want, stack.worldLog.query(filter), "$label: query $i, $filter")
            if (filter.offset != 0 || filter.limit != Int.MAX_VALUE) continue
            val got = stack.worldLog.queryTogether(stack.log, filter, structureEnds = true).first.map { it.seq.raw }.toSet()
            assertEquals(ends(newest.filter { admits(filter, it) }), got, "$label: ends of $filter")
        }
        for (pos in written.map { it.at }.distinct().shuffled(Random(1)).take(150)) {
            val want = newest.filter { it.at == pos }.take(20)
            assertEquals(want, stack.worldLog.at(pos, 20), "$label: history at $pos")
        }
    }

    private fun capsules(stack: Stack, tag: Tag): Int =
        stack.storage.engine.snapshot().use { s -> s.scan(tag.prefix).use { c -> var n = 0; while (c.next()) n++; n } }

    @Test
    fun `fragments and capsules answer alike, and a reopened store answers the same`(@TempDir dir: Path) = runTest {
        val written: List<WorldChange>
        Stack(dir).use { stack ->
            val writer = Writer(stack, 7)
            writer.write(1500)
            assertTrue(capsules(stack, Tag.CHRONO_FRAGMENT) > 0, "single appends leave fragments")
            assertLog(stack, writer.written, "fragments")
            Chrono.of(stack.storage).mergeAll()
            assertEquals(0, capsules(stack, Tag.CHRONO_FRAGMENT), "a merge folds every fragment in")
            assertTrue(capsules(stack, Tag.CHRONO_CAPSULE) > 0)
            assertLog(stack, writer.written, "merged")
            writer.write(600)
            assertLog(stack, writer.written, "merged then more")
            written = writer.written.toList()
        }
        Stack(dir).use { stack -> assertLog(stack, written, "reopened") }
    }

    @Test
    fun `one unit of thousands of changes lands as one fragment per tile`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val writer = Writer(stack, 17)
            stack.storage.batched { writer.write(3000) }
            val fragments = capsules(stack, Tag.CHRONO_FRAGMENT)
            val tiles = writer.written.map { Pair(it.at.world, (it.at.x shr 4) to (it.at.z shr 4)) }.toSet().size
            assertTrue(fragments <= tiles * 2 + 2, "$fragments fragments for $tiles tiles")
            repeat(4) { stack.storage.batched { writer.write(300) } }
            assertLog(stack, writer.written, "huge batches")
        }
    }

    @Test
    fun `a rolled back savepoint takes its changes back`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val writer = Writer(stack, 23)
            writer.write(200)
            val kept = writer.written.toList()
            stack.storage.batched {
                val mark = stack.storage.mark()
                writer.write(50)
                stack.storage.rollbackTo(mark)
            }
            assertLog(stack, kept, "after a rollback to a mark")
        }
    }

    @Test
    fun `a purge by player takes out exactly that player's changes`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val writer = Writer(stack, 29)
            writer.write(1200)
            Chrono.of(stack.storage).mergeAll()
            purgeSome(stack.storage, PurgeSpec(setOf(PurgeCategory.BLOCKS), PurgeFilter(player = UUID(0L, 2L))))
            assertLog(stack, writer.written.filter { it.causedBy != alex }, "purged alex")
        }
    }

    @Test
    fun `appending the same sequence twice is refused`(@TempDir dir: Path) = runTest {
        Stack(dir).use { stack ->
            val change = WorldChange(Seq(42), ActionKind.BLOCK_PLACE, CauseKind.PLAYER_ACTION, steve, millis0, BlockPos(overworld, 1, 64, 1), ChangeSubject.Block(air, shapes[0]))
            stack.worldLog.append(change)
            val refused = runCatching { stack.worldLog.append(change) }
            assertTrue(refused.isFailure, "a second append of seq 42 went through")
        }
    }
}
