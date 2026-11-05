package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.*
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.BlockChange
import com.tracel.storage.codec.EntityChange
import com.tracel.storage.codec.Extras
import com.tracel.storage.codec.Scalar
import com.tracel.storage.codec.Section
import com.tracel.storage.codec.Wchg
import com.tracel.storage.intern.Interning
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * World change records back into [WorldChange]s: one record,
 * or a section delta expanded to every block it stands for.
 */
internal class WorldDecoder(private val interning: Interning) {
    private val simpleShapes = ConcurrentHashMap<BlockDataKey, BlockShape>()

    /** Interned IDs of one page of records, resolved once. */
    class Resolved(
        val worlds: Int2ObjectMap<WorldId>,
        val shapes: Int2ObjectMap<BlockShape>,
        val blockData: Int2ObjectMap<BlockDataKey>,
        val holders: Int2ObjectMap<HolderId>,
        val entityTypes: Int2ObjectMap<EntityTypeKey>,
    )

    fun sectionChangeAt(
        unit: StorageUnit,
        record: MemorySegment,
        index: Int,
        packed: Int,
    ): WorldChange {
        val worldId = Wchg.world[record]
        val causedById = Wchg.causedBy[record]
        val extras = Section.extras(record, index)
        return WorldChange(
            Seq(Section.baseSeq(record) + index),
            Wchg.action[record],
            Wchg.cause[record],
            if (causedById == 0) null else interning.resolveHolder(unit, causedById),
            Wchg.epochMillis[record],
            BlockPos(
                interning.resolveWorld(unit, worldId),
                Wchg.x[record] + Section.positionX(packed),
                Wchg.y[record] + Section.positionY(packed),
                Wchg.z[record] + Section.positionZ(packed),
            ),
            ChangeSubject.Block(
                sectionShape(unit, Section.before(record, index), extras?.before, null),
                sectionShape(unit, Section.after(record, index), extras?.after, null),
            ),
        )
    }

    /** One record as a change, or a section delta as the list of every change it stands for. */
    fun expandOne(
        unit: StorageUnit,
        seq: Long,
        record: MemorySegment,
        resolved: Resolved?,
        filter: LookupFilter?,
    ): Any = if (Wchg.kind[record] != Wchg.SECTION) {
        decode(unit, seq, record, resolved)
    } else {
        ArrayList<WorldChange>().also { expandInto(unit, seq, record, resolved, filter, it) }
    }

    /** Every interned id the records ([pick]ed ones only, if given) name, resolved once for the page. */
    fun resolveAll(unit: StorageUnit, records: List<MemorySegment>, pick: BooleanArray?): Resolved {
        val worldIds = IntOpenHashSet()
        val dataIds = IntOpenHashSet()
        val holderIds = IntOpenHashSet()
        val typeIds = IntOpenHashSet()
        for (i in records.indices) {
            if (pick != null && !pick[i]) continue
            val record = records[i]
            worldIds.add(Wchg.world[record])
            val causedBy = Wchg.causedBy[record]
            if (causedBy != 0) holderIds.add(causedBy)
            when (Wchg.kind[record]) {
                Wchg.BLOCK -> {
                    dataIds.add(BlockChange.before[record])
                    dataIds.add(BlockChange.after[record])
                }

                Wchg.SECTION -> {
                    val palette = Section.paletteSize(record)
                    for (slot in 0 until palette) dataIds.add(Section.paletteAt(record, slot))
                }

                else -> typeIds.add(EntityChange.type[record])
            }
        }

        val worlds = Int2ObjectOpenHashMap<WorldId>(worldIds.size)
        for (id in worldIds.toIntArray()) worlds.put(id, interning.resolveWorld(unit, id))
        val blockData = Int2ObjectOpenHashMap<BlockDataKey>(dataIds.size)
        val shapes = Int2ObjectOpenHashMap<BlockShape>(dataIds.size)
        for (id in dataIds.toIntArray()) {
            val data = interning.resolveBlockData(unit, id)
            blockData.put(id, data)
            shapes.put(id, simpleShapes.getOrPut(data) { BlockShape(data) })
        }
        val holders = Int2ObjectOpenHashMap<HolderId>(holderIds.size)
        for (id in holderIds.toIntArray()) holders.put(id, interning.resolveHolder(unit, id))
        val entityTypes = Int2ObjectOpenHashMap<EntityTypeKey>(typeIds.size)
        for (id in typeIds.toIntArray()) entityTypes.put(id, interning.resolveEntityType(unit, id))
        return Resolved(worlds, shapes, blockData, holders, entityTypes)
    }

    fun expandInto(
        unit: StorageUnit,
        seq: Long,
        record: MemorySegment,
        resolved: Resolved?,
        filter: LookupFilter?,
        out: MutableList<WorldChange>,
    ) {
        if (Wchg.kind[record] != Wchg.SECTION) {
            out += decode(unit, seq, record, resolved)
            return
        }

        val worldId = Wchg.world[record]
        val world = resolved?.worlds?.get(worldId) ?: interning.resolveWorld(unit, worldId)
        val cornerX = Wchg.x[record]
        val cornerY = Wchg.y[record]
        val cornerZ = Wchg.z[record]
        val action = Wchg.action[record]
        val cause = Wchg.cause[record]
        val millis = Wchg.epochMillis[record]
        val causedById = Wchg.causedBy[record]
        val causedBy = when {
            causedById == 0 -> null
            else -> resolved?.holders?.get(causedById) ?: interning.resolveHolder(unit, causedById)
        }
        val baseSeq = Section.baseSeq(record)
        QueryProbe.expandedDelta(Section.count(record).toLong())
        val region = filter?.region
        val material = filter?.material

        // Palette resolved once per delta, slots read in one go: per block only the position is left to decode
        val palette = Section.palette(record)
        val shapes = Array(palette.size) { sectionShape(unit, palette[it], null, resolved) }
        val wanted = if (material == null) null else {
            val aliases = filter.blockMaterials
            BooleanArray(palette.size) { WorldFilter.matches(shapes[it].data.value, material, aliases) }
        }
        val slots = Section.slots(record)
        val count = slots.size / 2
        val extrasAll = Section.extrasAll(record)
        if (out is ArrayList<WorldChange>) out.ensureCapacity(out.size + count)

        Section.forEachPosition(record) { index, packed ->
            val x = cornerX + Section.positionX(packed)
            val y = cornerY + Section.positionY(packed)
            val z = cornerZ + Section.positionZ(packed)
            if (region != null && !region.containsBlock(x, y, z)) return@forEachPosition

            val beforeSlot = slots[index]
            val afterSlot = slots[count + index]
            if (wanted != null && !wanted[beforeSlot] && !wanted[afterSlot]) return@forEachPosition

            val extras = extrasAll?.get(index)
            out += WorldChange(
                Seq(baseSeq + index),
                action,
                cause,
                causedBy,
                millis,
                BlockPos(world, x, y, z),
                ChangeSubject.Block(
                    if (extras == null) shapes[beforeSlot] else sectionShape(
                        unit,
                        palette[beforeSlot],
                        extras.before,
                        resolved
                    ),
                    if (extras == null) shapes[afterSlot] else sectionShape(
                        unit,
                        palette[afterSlot],
                        extras.after,
                        resolved
                    ),
                ),
            )
        }
    }

    /** Builds a shape from a section delta's palette index and optional extras. */
    private fun sectionShape(
        unit: StorageUnit,
        dataId: Int,
        extras: ByteArray?,
        resolved: Resolved?,
    ): BlockShape {
        val data = resolved?.blockData?.get(dataId) ?: interning.resolveBlockData(unit, dataId)
        if (extras == null || extras.isEmpty()) {
            return simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        return BlockShape(data, Extras.decodeBlock(extras))
    }

    fun decode(unit: StorageUnit, seq: Long, record: MemorySegment, resolved: Resolved? = null): WorldChange {
        val version = Wchg.version[record]
        check(version == Scalar.VERSION) {
            "world change at seq $seq was written by codec v$version, this build reads v${Scalar.VERSION}."
        }

        val worldId = Wchg.world[record]
        val at = BlockPos(
            resolved?.worlds?.get(worldId) ?: interning.resolveWorld(unit, worldId),
            Wchg.x[record],
            Wchg.y[record],
            Wchg.z[record],
        )

        val subject = when (val kind = Wchg.kind[record]) {
            Wchg.BLOCK -> ChangeSubject.Block(
                blockShape(
                    unit,
                    BlockChange.before[record],
                    BlockChange.beforeExtras.length(record),
                    record,
                    before = true,
                    resolved = resolved
                ),
                blockShape(
                    unit,
                    BlockChange.after[record],
                    BlockChange.afterExtras.length(record),
                    record,
                    before = false,
                    resolved = resolved
                ),
            )

            Wchg.ENTITY -> {
                val action = Wchg.action[record]
                val typeId = EntityChange.type[record]
                val type = resolved?.entityTypes?.get(typeId) ?: interning.resolveEntityType(unit, typeId)
                val before = Extras.decodeShape(type, at, EntityChange.beforeExtras.slice(record))
                    ?: if (action == ActionKind.ENTITY_SPAWN) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                val after = Extras.decodeShape(type, at, EntityChange.afterExtras.slice(record))
                    ?: if (action == ActionKind.ENTITY_REMOVE) null else EntityShape(
                        type,
                        at.x + 0.5,
                        at.y.toDouble(),
                        at.z + 0.5
                    )
                ChangeSubject.Entity(EntityChange.entity[record], type, before, after)
            }

            else -> error("unrecognized world change kind: $kind.")
        }

        val causedById = Wchg.causedBy[record]
        return WorldChange(
            Seq(seq),
            Wchg.action[record],
            Wchg.cause[record],
            when {
                causedById == 0 -> null
                else -> resolved?.holders?.get(causedById) ?: interning.resolveHolder(unit, causedById)
            },
            Wchg.epochMillis[record],
            at,
            subject,
        )
    }

    /** The shared shape of [data] without extras. */
    fun shapeOf(data: BlockDataKey): BlockShape = simpleShapes.getOrPut(data) { BlockShape(data) }

    private fun blockShape(
        unit: StorageUnit,
        dataId: Int,
        extrasLen: Int,
        record: MemorySegment,
        before: Boolean,
        resolved: Resolved? = null,
    ): BlockShape {
        if (extrasLen == 0) resolved?.shapes?.get(dataId)?.let { return it }
        val data = resolved?.blockData?.get(dataId) ?: interning.resolveBlockData(unit, dataId)
        if (extrasLen == 0) {
            return simpleShapes.getOrPut(data) { BlockShape(data) }
        }
        val extras =
            if (before) Extras.decodeBlock(BlockChange.beforeExtras.slice(record)) else Extras.decodeBlock(BlockChange.afterExtras.slice(record))
        return BlockShape(data, extras)
    }
}
