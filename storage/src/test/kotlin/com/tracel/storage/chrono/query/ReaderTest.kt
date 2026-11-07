package com.tracel.storage.chrono.query

import com.tracel.storage.chrono.*
import com.tracel.storage.chrono.capsule.Capsule
import com.tracel.storage.chrono.capsule.CapsuleEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ReaderTest {
    private class Row(
        val tile: Tile,
        val seq: Long,
        val millis: Long,
        val cell: Int,
        val actor: Int,
        val kind: Int,
        val before: Int,
        val after: Int
    )

    private class Fixture(val reader: ChronoReader, val rows: List<Row>, val tiles: List<Tile>, val injected: Int)

    private fun fixture(seed: Int, shuffleTile: Boolean = false): Fixture {
        val random = Random(seed)
        val tiles = listOf(Tile.of(1, 12, -4), Tile.of(1, 13, -4), Tile.of(2, 0, 0), Tile.of(1, -900, 77))
        val all = ArrayList<Row>()
        var injected = 0
        for ((i, tile) in tiles.withIndex()) {
            val world = Worldlike(seed * 31 + i, tile, breakRate = 0.01, startSeq = (i + 1) * 1_000_000_000L)
            val rows = world.open(n = 1500 + random.nextInt(1500)).rows()
            injected += world.injectedBreaks
            for (r in 0 until rows.size) {
                all += Row(
                    tile,
                    rows.seq[r],
                    rows.millis[r],
                    rows.cell[r],
                    rows.actor[r],
                    rows.kind[r],
                    rows.before[r],
                    rows.after[r]
                )
            }
        }
        val timeline = all.sortedWith(compareBy({ it.millis }, { it.tile.packed }, { it.seq }))
        var next = 5_000_000L
        val renumbered = timeline.map { r ->
            next += 1 + random.nextInt(3)
            Row(r.tile, next, r.millis, r.cell, r.actor, r.kind, r.before, r.after)
        }
        all.clear()
        all += renumbered
        val feed = if (shuffleTile) all.shuffled(random) else all.sortedBy { it.seq }
        val source = MemoryCapsules()
        val encoder = CapsuleEncoder()
        val open = HashMap<Tile, RowsBuilder>()
        fun seal(tile: Tile, b: RowsBuilder) {
            if (b.size == 0) return
            source.add(tile, Capsule.of(encoder.encode(b.rows(), 0)))
            open[tile] = RowsBuilder()
        }
        for (r in feed) {
            val b = open.getOrPut(r.tile) { RowsBuilder() }
            b.add(r.seq, r.millis, r.cell, r.actor, r.kind, r.before, r.after)
            if (b.size >= 60 + random.nextInt(240)) seal(r.tile, b)
        }
        for ((tile, b) in open.toMap()) seal(tile, b)
        assertTrue(source.size > 10, "the fixture should seal plenty, sealed ${source.size}")
        return Fixture(ChronoReader(source), all, tiles, injected)
    }

    private fun Rows.list(tile: Tile): List<Row> =
        (0 until size).map { Row(tile, seq[it], millis[it], cell[it], actor[it], kind[it], before[it], after[it]) }

    private fun key(r: Row) = listOf(r.tile, r.seq, r.millis, r.cell, r.actor, r.kind, r.before, r.after)

    @Test
    fun `select agrees with brute force for every kind of sieve`() {
        for (seed in 1..4) {
            val f = fixture(seed)
            val since = f.rows.map { it.millis }.sorted()[f.rows.size / 3]
            val sieves = listOf(
                Sieve.ALL,
                Sieve(since = since),
                Sieve(actors = IdTest.of(listOf(41))),
                Sieve(kinds = IdTest.none(listOf(8))),
                Sieve(states = IdTest.of(listOf(7, 9))),
                Sieve(box = Box(minX = 12 * 16 + 4, maxX = 12 * 16 + 9, minY = 63, maxY = 66)),
                Sieve(
                    since = since,
                    until = since + 600_000,
                    actors = IdTest.of(listOf(0, 42)),
                    states = IdTest.of(listOf(100, 107))
                ),
            )
            for ((i, sieve) in sieves.withIndex()) {
                val got = f.reader.select(f.tiles, sieve).flatMap { it.rows.list(it.tile) }.map(::key)
                    .sortedBy { it.toString() }
                val want = f.rows.filter { r ->
                    sieve.admitsTime(r.millis) && sieve.actors.admits(r.actor) && sieve.kinds.admits(r.kind) &&
                            sieve.box.contains(r.tile, Cell(r.cell)) &&
                            (sieve.states == null || sieve.states!!.admits(r.before) || sieve.states!!.admits(r.after))
                }.map(::key).sortedBy { it.toString() }
                assertEquals(want, got, "sieve $i, seed $seed")
            }
        }
    }

    @Test
    fun `selectAnywhere by actor and by recency agrees with select`() {
        val f = fixture(9)
        val since = f.rows.map { it.millis }.sorted()[f.rows.size / 2]
        for (actor in listOf(41, 42, 0)) {
            val sieve = Sieve(since = since, actors = IdTest.of(listOf(actor)))
            val got = f.reader.selectAnywhere(actor, sieve).flatMap { it.rows.list(it.tile) }.map(::key).toSet()
            val want = f.rows.filter { it.actor == actor && it.millis >= since }.map(::key).toSet()
            assertEquals(want, got, "actor $actor")
        }
        val recent = f.reader.selectAnywhere(null, Sieve(since = since)).sumOf { it.rows.size }
        assertEquals(f.rows.count { it.millis >= since }, recent)
    }

    @Test
    fun `history of a cell is its rows, newest last, capped`() {
        val f = fixture(3)
        val byCell = f.rows.groupBy { it.tile to it.cell }
        for ((tc, rows) in byCell.entries.sortedByDescending { it.value.size }.take(25)) {
            val (tile, cell) = tc
            val got = f.reader.history(tile, Cell(cell), limit = 7).list(tile).map(::key)
            val want = rows.sortedBy { it.seq }.takeLast(7).map(::key)
            assertEquals(want, got, "history of ${Cell(cell)}")
        }
    }

    @Test
    fun `ends are the oldest and newest change of every cell`() {
        val f = fixture(4)
        val sieve = Sieve(actors = IdTest.none(listOf(0)))
        val got = f.reader.ends(f.tiles, sieve).flatMap { it.rows.list(it.tile) }.map(::key).toSet()
        val want = f.rows.filter { it.actor != 0 }.groupBy { it.tile to it.cell }.values
            .flatMap { rs -> listOf(rs.minBy { it.seq }, rs.maxBy { it.seq }) }.map(::key).toSet()
        assertEquals(want, got)
    }

    @Test
    fun `rewind gives the state before since and the state now, fast path or not`() {
        for (shuffle in listOf(false, true)) {
            val f = fixture(5, shuffleTile = shuffle)
            val times = f.rows.map { it.millis }.sorted()
            for (q in listOf(0, f.rows.size / 4, f.rows.size / 2, f.rows.size - 20)) {
                val since = times[q]
                val box = if (q % 2 == 0) Box.EVERYWHERE else Box(minY = 63, maxY = 65)
                val got = f.reader.rewind(f.tiles, since, box).flatMap { rw ->
                    (0 until rw.size).map { Triple(rw.tile to rw.cells[it], rw.then[it], rw.now[it]) }
                }.toSet()
                val want = f.rows.filter { it.millis >= since && box.contains(it.tile, Cell(it.cell)) }
                    .groupBy { it.tile to it.cell }
                    .map { (k, rs) -> Triple(k, rs.minBy { it.seq }.before, rs.maxBy { it.seq }.after) }
                    .toSet()
                assertEquals(want, got, "rewind to $q of ${f.rows.size}, shuffled $shuffle")
            }
        }
    }

    @Test
    fun `gaps find every change that did not start where the last one ended`() {
        val f = fixture(6)
        val got = f.reader.gaps(f.tiles, Long.MIN_VALUE).sumOf { it.size }
        var want = 0
        for (rows in f.rows.groupBy { it.tile to it.cell }.values) {
            val sorted = rows.sortedBy { it.seq }
            for (i in 1 until sorted.size) if (sorted[i].before != sorted[i - 1].after) want++
        }
        assertEquals(want, got)
        assertTrue(want > 0, "the fixture injects breaks")
    }

    @Test
    fun `frames step back through time`() {
        val f = fixture(7)
        val times = f.rows.map { it.millis }.sorted()
        val moments = longArrayOf(times[times.size / 5], times[times.size / 2], times[times.size - 5])
        val frames = f.reader.frames(f.tiles, moments)
        assertEquals(3, frames.size)
        val changed = frames.map { frame -> frame.sumOf { it.size } }
        assertTrue(
            changed[0] >= changed[1] && changed[1] >= changed[2],
            "older moments see more changed cells: $changed"
        )
    }
}
