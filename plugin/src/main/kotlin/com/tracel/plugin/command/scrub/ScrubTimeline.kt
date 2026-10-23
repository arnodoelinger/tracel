package com.tracel.plugin.command.scrub

import com.tracel.api.actor.Actor
import com.tracel.api.history.BlockRecord
import com.tracel.plugin.adapter.block.BlockDataCache
import org.bukkit.block.data.BlockData
import java.time.Instant
import java.util.UUID

/**
 * Block history cut into at most [MAX_STEPS] steps, newest first:
 * what the mouse wheel moves through.
 */
internal class ScrubTimeline(
    private val log: StepLog,
    private val steps: List<Step>,
    private val palette: List<String>,
    val records: Long,
) : AutoCloseable {
    private val parsed = arrayOfNulls<BlockData>(palette.size)

    /** How far back it goes. */
    val size: Int get() = steps.size

    /**
     * One notch of the wheel, from [oldest] to [newest]: [size] changes, [actor] doing most of them,
     * mostly to [block].
     *
     * Its entries on disk are [from] up to [to].
     */
    class Step(
        val newest: Instant,
        val oldest: Instant,
        val size: Long,
        val actor: Actor?,
        val block: String,
        internal val from: Long,
        internal val to: Long,
    )

    /** The step that moving to [depth] took back last: the oldest one that is back. */
    fun step(depth: Int): Step = steps[depth - 1]

    /** What moving over the [index]th step reads. */
    fun entries(index: Int): Long = steps[index].to - steps[index].from

    /** The block state under [id], as text. */
    fun state(id: Int): String = palette[id]

    /** The block state under [id], or `null` when this server cannot read it. */
    fun data(id: Int): BlockData? = parsed[id] ?: BlockDataCache.of(palette[id])?.also { parsed[id] = it }

    /**
     * Visits what every block should show after moving from depth [from] to depth [to],
     * as a packed position and the ID of its state.
     *
     * A block that changed in several steps is visited more than once, the last visit the one
     * that counts.
     */
    fun move(from: Int, to: Int, visit: (pos: Long, state: Int) -> Unit) {
        if (to > from) {
            for (i in from until to) log.scan(steps[i].from, steps[i].to, reverse = false) { pos, before, _ ->
                visit(pos, before)
            }
        } else {
            for (i in from - 1 downTo to) log.scan(steps[i].from, steps[i].to, reverse = true) { pos, _, after ->
                visit(pos, after)
            }
        }
    }

    override fun close() = log.close()

    companion object {
        const val MAX_STEPS = 1024
        const val GAP_MILLIS = 3_000L
        const val STEP_CAP = 400L
    }
}

/** Builds a [ScrubTimeline] from block records, newest first, as they are read. */
internal class ScrubTimelineBuilder(
    private val world: UUID,
    private val maxSteps: Int = ScrubTimeline.MAX_STEPS,
) : AutoCloseable {
    private class Pending(
        var newest: Long,
        var oldest: Long,
        var size: Long,
        var from: Long,
        var to: Long,
        val actors: HashMap<Actor?, Long>,
        val blocks: HashMap<String, Long>,
    ) {
        fun absorb(older: Pending) {
            oldest = older.oldest
            size += older.size
            to = older.to
            older.actors.forEach { (actor, n) -> actors.merge(actor, n, Long::plus) }
            older.blocks.forEach { (block, n) -> blocks.merge(block, n, Long::plus) }
        }
    }

    private val log = StepLog()
    private val palette = ArrayList<String>()
    private val ids = HashMap<String, Int>()
    private val made = ArrayList<Pending>()
    private var gap = ScrubTimeline.GAP_MILLIS
    private var cap = ScrubTimeline.STEP_CAP
    private var handedOver = false

    /** Block changes taken in so far. */
    var records = 0L; private set

    // The step being made: for each block the state it had before its oldest change and after its newest
    private val open = HashMap<Long, Long>()
    private var openActors = HashMap<Actor?, Long>()
    private var openBlocks = HashMap<String, Long>()
    private var openSize = 0L
    private var openNewest = 0L
    private var openOldest = 0L

    /**
     * Takes in the next record, which must be older than the last.
     *
     * Clicks that changed nothing and records already rolled back are left out.
     */
    fun add(record: BlockRecord) {
        if (record.place.world != world || record.rolledBack != null || record.before == record.after) return
        val millis = record.at.toEpochMilli()
        if (openSize > 0 && (openSize >= cap || openOldest - millis >= gap)) closeStep()
        if (openSize == 0L) openNewest = millis
        openOldest = millis
        openSize++
        records++

        val pos = Packed.of(record.place.x, record.place.y, record.place.z)
        val before = id(record.before)
        val after = id(record.after)
        val kept = open[pos]
        open[pos] = if (kept == null) pack(before, after) else pack(before, kept.toInt())
        openActors.merge(record.actor, 1L, Long::plus)
        val shown = if (record.after.substringBefore('[') in AIR) record.before else record.after
        openBlocks.merge(shown.substringBefore('['), 1L, Long::plus)
    }

    /**
     * The timeline of everything taken in.
     *
     * The builder is spent after this.
     */
    fun build(): ScrubTimeline {
        closeStep()
        log.flush()
        handedOver = true
        val steps = made.map {
            ScrubTimeline.Step(
                newest = Instant.ofEpochMilli(it.newest),
                oldest = Instant.ofEpochMilli(it.oldest),
                size = it.size,
                actor = it.actors.maxByOrNull { entry -> entry.value }?.key,
                block = it.blocks.maxByOrNull { entry -> entry.value }?.key ?: "",
                from = it.from,
                to = it.to,
            )
        }
        return ScrubTimeline(log, steps, palette.toList(), records)
    }

    override fun close() {
        if (!handedOver) log.close()
    }

    private fun closeStep() {
        if (openSize == 0L) return
        val from = log.entries
        for ((pos, both) in open) log.append(pos, (both ushr 32).toInt(), both.toInt())
        made += Pending(openNewest, openOldest, openSize, from, log.entries, openActors, openBlocks)
        open.clear()
        openActors = HashMap()
        openBlocks = HashMap()
        openSize = 0
        while (made.size > maxSteps) widen()
    }

    // Doubles what makes a step and joins the steps that fit together now
    private fun widen() {
        gap *= 2
        cap *= 2
        val joined = ArrayList<Pending>()
        for (step in made) {
            val last = joined.lastOrNull()
            if (last != null && last.size + step.size <= cap && last.oldest - step.newest < gap) last.absorb(step)
            else joined += step
        }
        made.clear()
        made += joined
    }

    private fun id(state: String): Int = ids.getOrPut(state) {
        palette += state
        palette.size - 1
    }

    private fun pack(before: Int, after: Int): Long = (before.toLong() shl 32) or (after.toLong() and 0xFFFFFFFFL)

    private companion object {
        val AIR = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air")
    }
}
