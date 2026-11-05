package com.tracel.storage.ports.log

import com.tracel.storage.codec.Index
import com.tracel.storage.codec.Extras
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.log.LogKind
import com.tracel.model.log.Seq
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockShape
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.capture.RawBlockEdits
import com.tracel.storage.codec.Keys
import com.tracel.storage.codec.Tag
import com.tracel.storage.codec.BlockChange
import com.tracel.storage.codec.EntityChange
import com.tracel.storage.codec.Section
import com.tracel.storage.codec.SectionExtras
import com.tracel.storage.intern.Interning
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap

/**
 * The write side of [WorldLog]: one record per change under its sequence,
 * a per-coordinate row so "what stood here before" is one prefix scan, and the actor,
 * time and spatial rows every change shares with [TransactionLog].
 *
 * Append-only. A bookkeeping change gets its record and the rows that find it by block or entity,
 * and none of the actor, time or spatial rows a lookup searches.
 */
internal class WorldWriter(private val storage: TracelStorage) {
    private val interning: Interning get() = storage.interning

    suspend fun append(change: WorldChange) {
        storage.write {
            val seq = change.seq.raw
            check(get(Tag.WCHG[seq]) == null) {
                "world change at ${change.seq} already appended; the log is append-only."
            }

            val causedById = change.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val worldId = interning.internWorld(this, change.at.world)
            val (_, x, y, z) = change.at
            val subject = change.subject

            var beforeDataId = -1
            var afterDataId = -1
            var inlineFlags = Index.INLINE_NEEDS_RECORD

            val record = when (subject) {
                is ChangeSubject.Block -> {
                    beforeDataId = interning.internBlockData(this, subject.before.data)
                    afterDataId = interning.internBlockData(this, subject.after.data)
                    if (subject.before.extras == null && subject.after.extras == null) inlineFlags = 0
                    BlockChange.of(
                        change.action,
                        change.cause,
                        causedById,
                        worldId,
                        x, y, z,
                        change.epochMillis,
                        beforeDataId,
                        afterDataId,
                        Extras.block(subject.before.extras),
                        Extras.block(subject.after.extras),
                    )
                }

                is ChangeSubject.Entity -> EntityChange.of(
                    change.action,
                    change.cause,
                    causedById,
                    worldId,
                    x, y, z,
                    change.epochMillis,
                    interning.internEntityType(this, subject.type),
                    subject.entity,
                    Extras.shape(subject.before),
                    Extras.shape(subject.after),
                )
            }

            put(Tag.WCHG[seq], record)
            put(Tag.WCHG_AT[worldId, x, y, z, seq], NONE)
            if (subject is ChangeSubject.Entity) put(Tag.WCHG_ENTITY[subject.entity, seq], NONE)
            if (change.cause.isBookkeeping) return@write
            lookupRows(
                seq, change.epochMillis, change.cause, causedById,
                Tag.SPATIAL[worldId, x shr 4, z shr 4, change.epochMillis, Keys.yBin(y), seq],
                Index.inline(
                    LogKind.WORLD, change.cause, x, y, z,
                    change.action, beforeDataId, afterDataId, causedById, inlineFlags,
                ),
            )
        }
    }

    suspend fun appendAll(edits: BlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val real = edits.edits.filter { it.before != it.after }
        if (real.isEmpty()) return 0

        val bySection = Long2ObjectLinkedOpenHashMap<MutableList<BlockEdit>>()
        for (edit in real) {
            val at = edit.at
            val section = sectionOf(at.x, at.y, at.z)
            var group = bySection.get(section)
            if (group == null) {
                group = ArrayList()
                bySection.put(section, group)
            }
            group += edit
        }

        for (group in bySection.values) {
            if (group.size >= SECTION_DELTA_FROM) {
                appendSection(group, edits, nextSeqRange(group.size))
            } else {
                for ((at, before, after) in group) {
                    append(
                        WorldChange(
                            nextSeqRange(1), edits.action, edits.cause, edits.causedBy, edits.epochMillis,
                            at, ChangeSubject.Block(before, after),
                        )
                    )
                }
            }
        }
        return real.size
    }

    private suspend fun appendSection(group: List<BlockEdit>, edits: BlockEdits, base: Seq) {
        val ordered = group.sortedBy { Section.position(it.at.x, it.at.y, it.at.z) }
        val count = ordered.size
        val first = ordered.first().at
        val sectionX = first.x shr 4
        val sectionY = first.y shr 4
        val sectionZ = first.z shr 4

        storage.write {
            val baseSeq = base.raw
            check(get(Tag.WCHG[baseSeq]) == null) {
                "world change at $base already appended; the log is append-only."
            }
            val causedById = edits.causedBy?.let { interning.internHolder(this, it) } ?: 0
            val worldId = interning.internWorld(this, first.world)

            val positions = IntArray(count)
            val before = IntArray(count)
            val after = IntArray(count)
            val extras = ArrayList<SectionExtras>()
            for (i in 0 until count) {
                val edit = ordered[i]
                positions[i] = Section.position(edit.at.x, edit.at.y, edit.at.z)
                before[i] = interning.internBlockData(this, edit.before.data)
                after[i] = interning.internBlockData(this, edit.after.data)
                if (edit.before.extras != null || edit.after.extras != null) {
                    extras += SectionExtras(
                        i,
                        Extras.block(edit.before.extras),
                        Extras.block(edit.after.extras),
                    )
                }
            }

            put(
                Tag.WCHG[baseSeq],
                Section.delta(
                    edits.action, edits.cause, causedById, worldId,
                    sectionX, sectionY, sectionZ, edits.epochMillis, baseSeq,
                    positions, count, before, after, extras,
                ),
            )
            put(Tag.WCHG_AT_SECTION[worldId, sectionX, sectionY, sectionZ, baseSeq], NONE)
            if (edits.cause.isBookkeeping) return@write
            lookupRows(
                baseSeq, edits.epochMillis, edits.cause, causedById,
                Tag.SPATIAL[worldId, sectionX, sectionZ, edits.epochMillis, Keys.yBin(first.y), baseSeq],
                Index.section(
                    LogKind.WORLD, edits.cause, edits.action,
                    sectionX shl 4, sectionY shl 4, sectionZ shl 4, count, causedById,
                ),
            )
        }
    }

    /**
     * [appendAll] for edits that never left their interned IDs: no shape is resolved only to be interned again,
     * the whole event takes one range of sequences and lands in one write.
     */
    suspend fun appendRaw(raw: RawBlockEdits, nextSeqRange: suspend (Int) -> Seq): Int {
        val n = raw.count
        if (n == 0) return 0
        if (n >= RAW_MAX_EDITS) return appendAll(raw.resolved(), nextSeqRange)
        val coordinates = raw.coordinates

        val sectionIds = Long2IntOpenHashMap().apply { defaultReturnValue(-1) }
        val keys = LongArray(n)
        for (i in 0 until n) {
            val x = coordinates[i * 3]
            val y = coordinates[i * 3 + 1]
            val z = coordinates[i * 3 + 2]
            val sectionKey = sectionOf(x, y, z)
            var section = sectionIds.get(sectionKey)
            if (section < 0) {
                section = sectionIds.size
                sectionIds.put(sectionKey, section)
            }
            keys[i] = (section.toLong() shl 32) or (Section.position(x, y, z).toLong() shl 20) or i.toLong()
        }
        keys.sort()

        val base = nextSeqRange(n).raw
        val bookkeeping = raw.cause.isBookkeeping
        storage.write {
            check(get(Tag.WCHG[base]) == null) {
                "world change at ${Seq(base)} already appended; the log is append-only."
            }
            val worldId = raw.worldId
            val causedById = raw.causedById
            val millis = raw.epochMillis
            val noExtras = Extras.block(null)
            var seq = base
            var from = 0
            while (from < n) {
                val section = (keys[from] ushr 32).toInt()
                var until = from + 1
                while (until < n && (keys[until] ushr 32).toInt() == section) until++
                val count = until - from
                val first = (keys[from] and 0xFFFFF).toInt()
                val x0 = coordinates[first * 3]
                val y0 = coordinates[first * 3 + 1]
                val z0 = coordinates[first * 3 + 2]

                if (count >= SECTION_DELTA_FROM) {
                    val positions = IntArray(count)
                    val before = IntArray(count)
                    val after = IntArray(count)
                    for (k in 0 until count) {
                        val key = keys[from + k]
                        val i = (key and 0xFFFFF).toInt()
                        positions[k] = ((key ushr 20) and 0xFFF).toInt()
                        before[k] = raw.befores[i]
                        after[k] = raw.afters[i]
                    }
                    val sectionX = x0 shr 4
                    val sectionY = y0 shr 4
                    val sectionZ = z0 shr 4
                    put(
                        Tag.WCHG[seq],
                        Section.delta(
                            raw.action, raw.cause, causedById, worldId,
                            sectionX, sectionY, sectionZ, millis, seq,
                            positions, count, before, after, emptyList(),
                        ),
                    )
                    put(Tag.WCHG_AT_SECTION[worldId, sectionX, sectionY, sectionZ, seq], NONE)
                    if (!bookkeeping) lookupRows(
                        seq, millis, raw.cause, causedById,
                        Tag.SPATIAL[worldId, sectionX, sectionZ, millis, Keys.yBin(y0), seq],
                        Index.section(
                            LogKind.WORLD, raw.cause, raw.action,
                            sectionX shl 4, sectionY shl 4, sectionZ shl 4, count, causedById,
                        ),
                    )
                    seq += count
                } else {
                    for (k in from until until) { // TODO: parallelize?
                        val i = (keys[k] and 0xFFFFF).toInt()
                        val x = coordinates[i * 3]
                        val y = coordinates[i * 3 + 1]
                        val z = coordinates[i * 3 + 2]
                        put(
                            Tag.WCHG[seq],
                            BlockChange.of(
                                raw.action, raw.cause, causedById, worldId, x, y, z, millis,
                                raw.befores[i], raw.afters[i], noExtras, noExtras,
                            ),
                        )
                        put(Tag.WCHG_AT[worldId, x, y, z, seq], NONE)
                        if (!bookkeeping) lookupRows(
                            seq, millis, raw.cause, causedById,
                            Tag.SPATIAL[worldId, x shr 4, z shr 4, millis, Keys.yBin(y), seq],
                            Index.inline(
                                LogKind.WORLD, raw.cause, x, y, z,
                                raw.action, raw.befores[i], raw.afters[i], causedById, 0,
                            ),
                        )
                        seq++
                    }
                }
                from = until
            }
        }
        return n
    }

    private suspend fun RawBlockEdits.resolved(): BlockEdits = storage.read {
        val world = interning.resolveWorld(this, worldId)
        val edits = ArrayList<BlockEdit>(count)
        for (i in 0 until count) {
            edits += BlockEdit(
                BlockPos(world, coordinates[i * 3], coordinates[i * 3 + 1], coordinates[i * 3 + 2]),
                BlockShape(interning.resolveBlockData(this, befores[i])),
                BlockShape(interning.resolveBlockData(this, afters[i])),
            )
        }
        BlockEdits(
            action,
            cause,
            if (causedById == 0) null else interning.resolveHolder(this, causedById),
            epochMillis,
            edits
        )
    }

    private fun sectionOf(x: Int, y: Int, z: Int): Long =
        ((x shr 4).toLong() and 0x3FFFFF shl 42) or
                ((z shr 4).toLong() and 0x3FFFFF shl 20) or
                ((y shr 4).toLong() and 0xFFFFF)

    /**
     * The rows a lookup finds a change by: who caused it, when, and where; the spatial row is [spatialKey] and
     * [spatialValue], built by the caller because a block and a section say different things there.
     */
    private fun StorageUnit.lookupRows(
        seq: Long,
        millis: Long,
        cause: CauseKind,
        causedById: Int,
        spatialKey: ByteArray,
        spatialValue: ByteArray,
    ) {
        if (causedById != 0) put(Tag.ACTOR[causedById, seq], Index.timed(LogKind.WORLD, millis, cause))
        put(Tag.TIME[millis, seq], OWN_LOG)
        put(spatialKey, spatialValue)
    }

    private companion object {
        val NONE = ByteArray(0)
        val OWN_LOG = Index.bare(LogKind.WORLD)

        const val SECTION_DELTA_FROM = 2
        const val RAW_MAX_EDITS = 1 shl 20
    }
}
