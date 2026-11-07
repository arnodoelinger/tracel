package com.tracel.storage.chrono

import kotlin.random.Random

class Worldlike(
    seed: Int,
    private val tile: Tile = Tile.of(1, 12, -4),
    private val breakRate: Double = 0.0,
    startSeq: Long = 1_000_000L,
    startMillis: Long = 1_700_000_000_000L,
) {
    private val random = Random(seed)
    private val world = HashMap<Int, Int>()
    private val extras = HashMap<Int, ByteArray?>()
    private var seq = startSeq
    private var millis = startMillis

    val stone = 1
    val air = 0
    val planks = 7
    val glass = 9
    val sign = 63
    val crops = IntArray(8) { 100 + it }
    val players = intArrayOf(41, 42, 43)

    var injectedBreaks = 0; private set

    fun into(capsule: RowsBuilder, n: Int): RowsBuilder {
        repeat(n) { step(capsule) }
        return capsule
    }

    fun open(n: Int): RowsBuilder = into(RowsBuilder(), n)

    private fun stateAt(cell: Int): Int = world[cell] ?: if (Cell(cell).y < 64) stone else air

    private fun step(capsule: RowsBuilder) {
        when (random.nextInt(100)) {
            in 0..34 -> build(capsule)
            in 35..64 -> farm(capsule)
            in 65..84 -> mine(capsule)
            in 85..97 -> sign(capsule)
            else -> crater(capsule)
        }
    }

    private fun build(capsule: RowsBuilder) {
        val cell = Cell.of(random.nextInt(4, 12), 64 + random.nextInt(0, 6), random.nextInt(4, 12)).packed
        val now = stateAt(cell)
        val next = if (now == air) (if (random.nextInt(5) == 0) glass else planks) else air
        change(capsule, cell, next, players[0], kind = if (next == air) 1 else 0)
    }

    private fun farm(capsule: RowsBuilder) {
        val cell = Cell.of(random.nextInt(0, 4), 63, random.nextInt(0, 4)).packed
        val now = stateAt(cell)
        val age = crops.indexOf(now)
        val next = if (age < 0 || age == 7) crops[0] else crops[age + 1]
        change(capsule, cell, next, if (age == 7) players[1] else 0, kind = if (age == 7) 1 else 8)
    }

    private fun mine(capsule: RowsBuilder) {
        val x = random.nextInt(0, 16)
        val cell = Cell.of(x, 40 + random.nextInt(0, 2), 8).packed
        if (stateAt(cell) == air) return
        change(capsule, cell, air, players[2], kind = 1)
    }

    private fun sign(capsule: RowsBuilder) {
        val cell = Cell.of(14, 65, 14).packed
        val text = "line ${random.nextInt(1000)}".toByteArray()
        change(capsule, cell, sign, players[0], kind = 3, extras = text)
    }

    private fun crater(capsule: RowsBuilder) {
        val cx = random.nextInt(2, 14)
        val cz = random.nextInt(2, 14)
        for (dx in -2..2) for (dz in -2..2) for (dy in 0..2) {
            val cell = Cell.of(cx + dx, 62 + dy, cz + dz).packed
            if (stateAt(cell) == air) continue
            change(capsule, cell, air, players[random.nextInt(3)], kind = 0x11, tick = false)
        }
        millis += 50
    }

    private fun change(
        capsule: RowsBuilder,
        cell: Int,
        next: Int,
        actor: Int,
        kind: Int,
        extras: ByteArray? = null,
        tick: Boolean = true
    ) {
        var before = stateAt(cell)
        var beforeExtras = this.extras[cell]
        if (breakRate > 0 && random.nextDouble() < breakRate && world.containsKey(cell)) {
            before = glass + 1000 + random.nextInt(3)
            beforeExtras = null
            injectedBreaks++
        }
        seq += 1 + if (random.nextInt(4) == 0) random.nextInt(1, 500) else 0
        if (tick) millis += random.nextLong(0, 4000)
        capsule.add(seq, millis, cell, actor, kind, before, next, beforeExtras, extras)
        world[cell] = next
        this.extras[cell] = extras
    }
}
