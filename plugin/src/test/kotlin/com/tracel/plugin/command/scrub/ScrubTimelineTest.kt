package com.tracel.plugin.command.scrub

import com.tracel.api.actor.Actor
import com.tracel.api.history.ActionKind
import com.tracel.api.history.BlockRecord
import com.tracel.api.history.CauseKind
import com.tracel.api.history.HistoryCursor
import com.tracel.api.world.BlockPlace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ScrubTimelineTest {
    private val world = UUID(0, 1)
    private val steve = Actor.Player(UUID(0, 2))
    private val stone = "minecraft:stone"
    private val dirt = "minecraft:dirt"
    private val air = "minecraft:air"

    private fun record(
        seconds: Long,
        x: Int,
        before: String,
        after: String,
        rolledBack: Instant? = null,
        y: Int = 64,
    ) = BlockRecord(
        at = Instant.ofEpochSecond(seconds),
        actor = steve,
        cause = CauseKind.PLAYER_ACTION,
        rolledBack = rolledBack,
        place = BlockPlace(world, x, y, 0),
        cursor = HistoryCursor(seconds * 1000, 0),
        action = ActionKind.BLOCK_BREAK,
        before = before,
        after = after,
    )

    private fun timeline(vararg newestFirst: BlockRecord, maxSteps: Int = ScrubTimeline.MAX_STEPS): ScrubTimeline {
        val builder = ScrubTimelineBuilder(world, maxSteps)
        newestFirst.forEach(builder::add)
        return builder.build()
    }

    private fun ScrubTimeline.shown(from: Int, to: Int): Map<Long, String> {
        val out = HashMap<Long, String>()
        move(from, to) { pos, state -> out[pos] = state(state) }
        return out
    }

    private fun at(x: Int, y: Int = 64) = Packed.of(x, y, 0)

    @Test
    fun `a quiet gap starts a new step, and newest comes first`() {
        val timeline = timeline(record(100, 1, stone, air), record(99, 2, stone, air), record(10, 3, dirt, air))
        try {
            assertEquals(2, timeline.size)
            assertEquals(2, timeline.step(1).size)
            assertEquals(1, timeline.step(2).size)
        } finally {
            timeline.close()
        }
    }

    @Test
    fun `going back shows what a block was before, coming forward what it became`() {
        val timeline = timeline(record(300, 1, dirt, air), record(200, 1, stone, dirt), record(100, 1, air, stone))
        try {
            assertEquals(3, timeline.size)
            assertEquals(mapOf(at(1) to dirt), timeline.shown(0, 1))
            assertEquals(mapOf(at(1) to stone), timeline.shown(1, 2))
            assertEquals(mapOf(at(1) to air), timeline.shown(2, 3))
            assertEquals(air, timeline.shown(0, 3)[at(1)])
            assertEquals(dirt, timeline.shown(3, 1)[at(1)])
            assertEquals(air, timeline.shown(3, 0)[at(1)])
            assertEquals(dirt, timeline.shown(2, 1)[at(1)])
        } finally {
            timeline.close()
        }
    }

    @Test
    fun `a block that changed twice in one step shows its oldest before and its newest after`() {
        val timeline = timeline(record(101, 1, dirt, air), record(100, 1, stone, dirt))
        try {
            assertEquals(1, timeline.size)
            assertEquals(stone, timeline.shown(0, 1)[at(1)])
            assertEquals(air, timeline.shown(1, 0)[at(1)])
        } finally {
            timeline.close()
        }
    }

    @Test
    fun `rolled back records, clicks that changed nothing and other worlds are left out`() {
        val elsewhere = record(150, 3, stone, air).copy(place = BlockPlace(UUID(0, 9), 3, 64, 0))
        val timeline = timeline(
            record(300, 1, stone, air, rolledBack = Instant.ofEpochSecond(400)),
            record(200, 2, dirt, dirt),
            elsewhere,
        )
        try {
            assertEquals(0, timeline.size)
        } finally {
            timeline.close()
        }
    }

    @Test
    fun `any amount of history ends up in at most as many steps as asked, and still scrubs through`() {
        val builder = ScrubTimelineBuilder(world, maxSteps = 16)
        for (i in 0 until 5_000) builder.add(record(100_000L - i * 10, i, stone, air, y = 64))
        val timeline = builder.build()
        try {
            assertTrue(timeline.size in 1..16, "steps: ${timeline.size}")
            assertEquals(5_000L, timeline.records)
            val everything = timeline.shown(0, timeline.size)
            assertEquals(5_000, everything.size)
            assertTrue(everything.values.all { it == stone })
            assertTrue(timeline.shown(timeline.size, 0).values.all { it == air })
        } finally {
            timeline.close()
        }
    }

    @Test
    fun `positions pack and come back, negative ones too`() {
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-1, -64, -1), Triple(29_999_999, 319, -29_999_999))) {
            val pos = Packed.of(x, y, z)
            assertEquals(Triple(x, y, z), Triple(Packed.x(pos), Packed.y(pos), Packed.z(pos)))
            val chunk = Packed.chunkOf(pos)
            val local = Packed.local(pos)
            assertEquals(x, Packed.localX(chunk, local))
            assertEquals(y, Packed.localY(local))
            assertEquals(z, Packed.localZ(chunk, local))
        }
    }

    @Test
    fun `the int map keeps what it is given`() {
        val map = IntIntMap()
        for (i in 0 until 1_000) map.put(i * 7, i)
        map.put(7, 5_000)
        assertEquals(1_000, map.size)
        var sum = 0L
        map.forEach { _, value -> sum += value }
        assertEquals((0 until 1_000).sumOf { it.toLong() } - 1 + 5_000, sum)
        assertNull(null)
    }
}
