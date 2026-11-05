package com.tracel.storage.codec

import com.tracel.engine.rollback.plan.step.LotContribution
import com.tracel.engine.rollback.plan.step.RollbackStep
import com.tracel.engine.rollback.plan.step.UnmadeOutput
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.item.Quantity
import com.tracel.model.lot.LotId
import com.tracel.model.transaction.TxnId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.OpaqueBytes
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.storage.ffm.Bytes.i16
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI16
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.readBytes
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet
import java.lang.foreign.MemorySegment
import java.util.*

// region World changes

/** The 32 bytes every world change starts with. */
object Wchg : Record() {
    const val BLOCK: Byte = 0
    const val ENTITY: Byte = 1
    const val SECTION: Byte = 2

    val version = i8()
    val kind = i8()
    val action = enum(ActionKind.entries)
    val cause = enum(CauseKind.entries)
    val causedBy = i32()
    val world = i32()
    val x = i32()
    val y = i32()
    val z = i32()
    val epochMillis = i64()
}

internal fun Writer.wchg(
    kind: Byte, action: ActionKind, cause: CauseKind, causedBy: Int, world: Int, x: Int, y: Int, z: Int, millis: Long,
) {
    Wchg.version(CODEC_VERSION); Wchg.kind(kind); Wchg.action(action); Wchg.cause(cause); Wchg.causedBy(causedBy)
    Wchg.world(world); Wchg.x(x); Wchg.y(y); Wchg.z(z); Wchg.epochMillis(millis)
}

object BlockChange : Record(Wchg) {
    val before = i32()
    val after = i32()
    val beforeExtras = blob16()
    val afterExtras = blob16()

    fun of(
        action: ActionKind, cause: CauseKind, causedBy: Int, world: Int, x: Int, y: Int, z: Int, millis: Long,
        was: Int, now: Int, wasExtras: ByteArray, nowExtras: ByteArray,
    ): ByteArray {
        Extras.check(wasExtras, nowExtras)
        return build(sized(wasExtras, nowExtras)) {
            wchg(Wchg.BLOCK, action, cause, causedBy, world, x, y, z, millis)
            before(was); after(now); beforeExtras(wasExtras); afterExtras(nowExtras)
        }
    }
}

object EntityChange : Record(Wchg) {
    val type = i32()
    val entity = uuid()
    val beforeExtras = blob16()
    val afterExtras = blob16()

    fun of(
        action: ActionKind, cause: CauseKind, causedBy: Int, world: Int, x: Int, y: Int, z: Int, millis: Long,
        typeId: Int, uuid: UUID, wasExtras: ByteArray, nowExtras: ByteArray,
    ): ByteArray {
        Extras.check(wasExtras, nowExtras)
        return build(sized(wasExtras, nowExtras)) {
            wchg(Wchg.ENTITY, action, cause, causedBy, world, x, y, z, millis)
            type(typeId); entity(uuid); beforeExtras(wasExtras); afterExtras(nowExtras)
        }
    }
}

object Extras {
    const val MAX_BYTES = OpaqueBytes.MAX_BYTES
    const val LINK_BYTES = OpaqueBytes.LINK_BYTES

    private const val OPAQUE: Byte = 0
    private const val POSED: Byte = 1
    private const val FALLING: Byte = 2
    private const val LEASHED: Byte = 3
    private const val RIDING: Byte = 4
    private const val INFLATED: Byte = 5

    private object Pose : Record() {
        val tag = i8()
        val x = f64()
        val y = f64()
        val z = f64()
        val yaw = f32()
        val pitch = f32()
    }

    private object Link : Record() {
        val tag = i8()
        val target = uuid()
    }

    fun block(extras: BlockExtras?): ByteArray = when (extras) {
        null -> EMPTY_BYTES
        is BlockExtras.Opaque -> opaque(extras.bytes)
    }

    fun entity(extras: EntityExtras?): ByteArray = when (extras) {
        null -> EMPTY_BYTES
        is EntityExtras.Opaque -> opaque(extras.bytes)
        is EntityExtras.Falling -> tagged(FALLING, extras.data.value.toByteArray(Charsets.UTF_8))
        is EntityExtras.Leashed -> link(LEASHED, extras.holder, extras.rest)
        is EntityExtras.Riding -> link(RIDING, extras.vehicle, extras.rest)
    }

    fun shape(shape: EntityShape?): ByteArray {
        if (shape == null) return EMPTY_BYTES
        val rest = entity(shape.extras)
        return Pose.build(Pose.sized(rest)) {
            Pose.tag(POSED); Pose.x(shape.x); Pose.y(shape.y); Pose.z(shape.z); Pose.yaw(shape.yaw); Pose.pitch(shape.pitch)
            rest.copyInto(v, Pose.size)
        }
    }

    fun decodeBlock(bytes: ByteArray): BlockExtras? = decodeBlock(MemorySegment.ofArray(bytes))

    fun decodeBlock(v: MemorySegment): BlockExtras? = when (if (v.byteSize() == 0L) null else v.i8(0)) {
        null -> null
        OPAQUE -> BlockExtras.Opaque(rest(v, 1))
        INFLATED -> BlockExtras.Opaque(Gzipped.deflate(rest(v, 1)))
        else -> error("unrecognized block extras tag: ${v.i8(0)}.")
    }

    fun decodeEntity(bytes: ByteArray): EntityExtras? = decodeEntity(MemorySegment.ofArray(bytes))

    fun decodeEntity(v: MemorySegment): EntityExtras? = when (if (v.byteSize() == 0L) null else v.i8(0)) {
        null -> null
        OPAQUE -> EntityExtras.Opaque(rest(v, 1))
        INFLATED -> EntityExtras.Opaque(Gzipped.deflate(rest(v, 1)))
        FALLING -> EntityExtras.Falling(BlockDataKey(String(rest(v, 1), Charsets.UTF_8)))
        LEASHED -> EntityExtras.Leashed(target(v), decodeEntity(v.asSlice(Link.size)))
        RIDING -> EntityExtras.Riding(target(v), decodeEntity(v.asSlice(Link.size)))
        else -> error("unrecognized entity extras tag: ${v.i8(0)}.")
    }

    fun decodeShape(type: EntityTypeKey, at: BlockPos, bytes: ByteArray): EntityShape? =
        decodeShape(type, at, MemorySegment.ofArray(bytes))

    fun decodeShape(type: EntityTypeKey, at: BlockPos, v: MemorySegment): EntityShape? = when (if (v.byteSize() == 0L) null else v.i8(0)) {
        null -> null
        POSED -> {
            check(v.byteSize() >= Pose.size) { "posed extras truncated at ${v.byteSize()} bytes." }
            EntityShape(type, Pose.x[v], Pose.y[v], Pose.z[v], Pose.yaw[v], Pose.pitch[v], decodeEntity(v.asSlice(Pose.size)))
        }

        OPAQUE, INFLATED -> EntityShape(type, at.x + 0.5, at.y.toDouble(), at.z + 0.5, extras = decodeEntity(v))
        else -> error("unrecognized entity extras tag: ${v.i8(0)}.")
    }

    fun check(before: ByteArray, after: ByteArray) = require(before.size <= MAX_BYTES && after.size <= MAX_BYTES) {
        "extras of ${before.size} / ${after.size} bytes do not fit a u16 length prefix."
    }

    private fun target(v: MemorySegment): UUID {
        check(v.byteSize() >= LINK_BYTES) { "linked extras truncated at ${v.byteSize()} bytes." }
        return Link.target[v]
    }

    private fun link(tag: Byte, target: UUID, rest: EntityExtras?): ByteArray {
        val nested = entity(rest)
        return Link.build(Link.sized(nested)) { Link.tag(tag); Link.target(target); nested.copyInto(v, Link.size) }
    }

    private fun opaque(bytes: ByteArray): ByteArray =
        Gzipped.inflate(bytes, limit = MAX_BYTES / 2)?.let { tagged(INFLATED, it) } ?: tagged(OPAQUE, bytes)

    private fun tagged(tag: Byte, payload: ByteArray): ByteArray =
        ByteArray(1 + payload.size).also { it[0] = tag; payload.copyInto(it, 1) }

    private fun rest(v: MemorySegment, from: Long): ByteArray = v.readBytes(from, (v.byteSize() - from).toInt())

    private fun ByteArray.copyInto(v: MemorySegment, at: Long) =
        MemorySegment.copy(MemorySegment.ofArray(this), 0L, v, at, size.toLong())
}

// endregion

// region Sections

class SectionExtras(val index: Int, val before: ByteArray, val after: ByteArray)

/** Where a structure step or a structure section is. */
object StructHead : Record() {
    val kind = i8()
    val world = i32()
    val x = i32()
    val y = i32()
    val z = i32()
}

object Section {
    const val POSITIONS = 4096
    const val STRUCT: Byte = 3

    @PublishedApi
    internal const val BITMAP_BYTES = POSITIONS / 8
    private const val BITMAP_FROM = BITMAP_BYTES / 2
    private const val FLAG_BITMAP = 1
    private const val FLAG_WIDE = 2

    const val WORLD_TAIL = 40L
    const val STRUCT_TAIL = 17L

    /** A world section: the head, then the seq its first edit took. */
    object Change : Record(Wchg) {
        val baseSeq = i64()
    }

    private object Tail : Record() {
        val count = i32()
        val flags = i8()
        val paletteSize = u16()
        private val gap = pad(1)
        val positionsAt = i32()
        val paletteAt = i32()
        val indicesAt = i32()
        val extrasAt = i32()
    }

    private object Extra : Record() {
        val index = i32()
        val before = blob16()
        val after = blob16()
    }

    fun delta(
        action: ActionKind, cause: CauseKind, causedBy: Int, world: Int, sectionX: Int, sectionY: Int, sectionZ: Int,
        millis: Long, baseSeq: Long, positions: IntArray, count: Int, before: IntArray, after: IntArray,
        extras: List<SectionExtras> = emptyList(),
    ): ByteArray = pack(WORLD_TAIL, positions, count, before, after, extras) {
        wchg(Wchg.SECTION, action, cause, causedBy, world, sectionX shl 4, sectionY shl 4, sectionZ shl 4, millis)
        Change.baseSeq(baseSeq)
    }

    fun structure(
        world: Int, sectionX: Int, sectionY: Int, sectionZ: Int, positions: IntArray, count: Int,
        target: IntArray, expected: IntArray, extras: List<SectionExtras>,
    ): ByteArray = pack(STRUCT_TAIL, positions, count, target, expected, extras) {
        StructHead.kind(STRUCT); StructHead.world(world)
        StructHead.x(sectionX shl 4); StructHead.y(sectionY shl 4); StructHead.z(sectionZ shl 4)
    }

    fun baseSeq(v: MemorySegment): Long = Change.baseSeq[v]

    fun count(v: MemorySegment, tail: Long = WORLD_TAIL): Int = Tail.count[v, tail]
    fun paletteSize(v: MemorySegment, tail: Long = WORLD_TAIL): Int = Tail.paletteSize[v, tail]
    fun isBitmap(v: MemorySegment, tail: Long = WORLD_TAIL): Boolean = Tail.flags[v, tail].toInt() and FLAG_BITMAP != 0
    fun positionsAt(v: MemorySegment, tail: Long = WORLD_TAIL): Long = Tail.positionsAt[v, tail].toLong()

    fun paletteAt(v: MemorySegment, slot: Int, tail: Long = WORLD_TAIL): Int = v.i32(Tail.paletteAt[v, tail] + slot * 4L)
    fun palette(v: MemorySegment, tail: Long = WORLD_TAIL): IntArray = IntArray(paletteSize(v, tail)) { paletteAt(v, it, tail) }

    fun before(v: MemorySegment, index: Int, tail: Long = WORLD_TAIL): Int = paletteAt(v, slot(v, index, tail), tail)
    fun after(v: MemorySegment, index: Int, tail: Long = WORLD_TAIL): Int = paletteAt(v, slot(v, count(v, tail) + index, tail), tail)

    /** Every before index, then every after index. */
    fun slots(v: MemorySegment, tail: Long = WORLD_TAIL): IntArray = IntArray(count(v, tail) * 2) { slot(v, it, tail) }

    inline fun forEachPosition(v: MemorySegment, tail: Long = WORLD_TAIL, action: (index: Int, packed: Int) -> Unit) {
        val count = count(v, tail)
        val at = positionsAt(v, tail)
        if (!isBitmap(v, tail)) {
            for (i in 0 until count) action(i, v.i16(at + i * 2L).toInt() and 0xFFFF)
            return
        }
        var index = 0
        var byte = 0
        while (index < count && byte < BITMAP_BYTES) {
            var bits = v.i8(at + byte).toInt() and 0xFF
            while (bits != 0) {
                action(index++, (byte shl 3) or Integer.numberOfTrailingZeros(bits))
                bits = bits and (bits - 1)
            }
            byte++
        }
    }

    /** The index of [packed] among the positions, or -1. */
    fun indexOf(v: MemorySegment, packed: Int, tail: Long = WORLD_TAIL): Int {
        val at = positionsAt(v, tail)
        if (!isBitmap(v, tail)) {
            var low = 0
            var high = count(v, tail) - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                val here = v.i16(at + mid * 2L).toInt() and 0xFFFF
                when {
                    here < packed -> low = mid + 1
                    here > packed -> high = mid - 1
                    else -> return mid
                }
            }
            return -1
        }
        val byte = packed shr 3
        val bits = v.i8(at + byte).toInt() and 0xFF
        if (bits and (1 shl (packed and 7)) == 0) return -1
        var index = Integer.bitCount(bits and ((1 shl (packed and 7)) - 1))
        for (b in 0 until byte) index += Integer.bitCount(v.i8(at + b).toInt() and 0xFF)
        return index
    }

    fun extras(v: MemorySegment, index: Int, tail: Long = WORLD_TAIL): SectionExtras? {
        eachExtra(v, tail) { at -> if (Extra.index[v, at] == index) return read(v, at) }
        return null
    }

    /** Every position's extras, first one wins; `null` when there are none at all. */
    fun extrasAll(v: MemorySegment, tail: Long = WORLD_TAIL): Array<SectionExtras?>? {
        if (v.i32(Tail.extrasAt[v, tail].toLong()) == 0) return null
        val out = arrayOfNulls<SectionExtras>(count(v, tail))
        eachExtra(v, tail) { at ->
            val which = Extra.index[v, at]
            if (which in out.indices && out[which] == null) out[which] = read(v, at)
        }
        return out
    }

    fun position(x: Int, y: Int, z: Int): Int = ((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)
    fun positionX(packed: Int): Int = packed and 15
    fun positionY(packed: Int): Int = (packed shr 8) and 15
    fun positionZ(packed: Int): Int = (packed shr 4) and 15

    private inline fun pack(
        tail: Long, positions: IntArray, count: Int, before: IntArray, after: IntArray, extras: List<SectionExtras>,
        head: Writer.() -> Unit,
    ): ByteArray {
        require(count > 0) { "a packed section with no positions says nothing." }
        val palette = IntLinkedOpenHashSet().apply { for (i in 0 until count) { add(before[i]); add(after[i]) } }.toIntArray()
        val wide = palette.size > 256
        val bitmap = count >= BITMAP_FROM
        val positionsAt = tail + Tail.size
        val paletteAt = positionsAt + if (bitmap) BITMAP_BYTES else count * 2
        val indicesAt = paletteAt + palette.size * 4
        val extrasAt = indicesAt + count * (if (wide) 4 else 2)
        val out = ByteArray((extrasAt + 4 + extras.sumOf { Extra.size + it.before.size + it.after.size }).toInt())
        val v = MemorySegment.ofArray(out)

        Writer(v, 0L).head()
        Tail.write(v, tail) {
            Tail.count(count)
            Tail.flags(((if (bitmap) FLAG_BITMAP else 0) or (if (wide) FLAG_WIDE else 0)).toByte())
            Tail.paletteSize(palette.size)
            Tail.positionsAt(positionsAt.toInt()); Tail.paletteAt(paletteAt.toInt())
            Tail.indicesAt(indicesAt.toInt()); Tail.extrasAt(extrasAt.toInt())
        }

        for (i in 0 until count) {
            val p = positions[i]
            if (bitmap) out[(positionsAt + (p shr 3)).toInt()] = (out[(positionsAt + (p shr 3)).toInt()].toInt() or (1 shl (p and 7))).toByte()
            else v.putI16(positionsAt + i * 2L, p.toShort())
        }
        for (i in palette.indices) v.putI32(paletteAt + i * 4L, palette[i])

        val slot = Int2IntOpenHashMap(palette.size).apply { defaultReturnValue(-1); palette.forEachIndexed { i, s -> put(s, i) } }
        for (i in 0 until count) {
            val b = slot.get(before[i])
            val a = slot.get(after[i])
            check(b >= 0 && a >= 0) { "a state is missing from its own palette." }
            if (wide) {
                v.putI16(indicesAt + i * 2L, b.toShort()); v.putI16(indicesAt + (count + i) * 2L, a.toShort())
            } else {
                v.putI8(indicesAt + i, b.toByte()); v.putI8(indicesAt + count + i, a.toByte())
            }
        }

        v.putI32(extrasAt, extras.size)
        var at = extrasAt + 4
        for (e in extras) {
            Extra.write(v, at) { Extra.index(e.index); Extra.before(e.before); Extra.after(e.after) }
            at = Extra.after.end(v, at)
        }
        return out
    }

    private fun isWide(v: MemorySegment, tail: Long): Boolean = Tail.flags[v, tail].toInt() and FLAG_WIDE != 0

    private fun slot(v: MemorySegment, i: Int, tail: Long): Int {
        val at = Tail.indicesAt[v, tail].toLong()
        return if (isWide(v, tail)) v.i16(at + i * 2L).toInt() and 0xFFFF else v.i8(at + i).toInt() and 0xFF
    }

    private inline fun eachExtra(v: MemorySegment, tail: Long, entry: (Long) -> Unit) {
        var at = Tail.extrasAt[v, tail].toLong()
        repeat(v.i32(at).also { at += 4 }) {
            entry(at)
            at = Extra.after.end(v, at)
        }
    }

    private fun read(v: MemorySegment, at: Long) = SectionExtras(Extra.index[v, at], Extra.before[v, at], Extra.after[v, at])
}

// endregion

// region Rollback plans

/** Structure steps and rollback steps as they sit in a job's plan. */
object Rollback {
    private const val SET_BLOCK: Byte = 0
    private const val SPAWN_ENTITY: Byte = 1
    private const val REMOVE_ENTITY: Byte = 2
    private const val CHANGE_ENTITY: Byte = 4

    private const val TAKE: Byte = 0
    private const val MINT: Byte = 1
    private const val DEBT: Byte = 2
    private const val UNMAKE: Byte = 3
    private const val UNMAKE_MANY: Byte = 4
    private const val TAKE_RUN: Byte = 5

    private object SetBlock : Record(StructHead) {
        val target = i32()
        val expected = i32()
        val targetExtras = blob16()
        val expectedExtras = blob16()
    }

    private object Entity : Record(StructHead) {
        val type = i32()
        val uuid = uuid()
        val x = f64()
        val y = f64()
        val z = f64()
        val yaw = f32()
        val pitch = f32()
        val extras = blob16()
    }

    private object Expected : Record() {
        val x = f64()
        val y = f64()
        val z = f64()
        val yaw = f32()
        val pitch = f32()
        val extras = blob16()
    }

    private object Take : Record() {
        val tag = i8()
        val lot = i64()
        val quantity = i64()
        val holder = i32()
    }

    private object TakeRun : Record() {
        val tag = i8()
        val holder = i32()
        val count = i32()
    }

    private object Mint : Record() {
        val tag = i8()
        val lot = i64()
        val quantity = i64()
        val reason = enum(SinkKind.entries)
    }

    private object Debt : Record() {
        val tag = i8()
        val lot = i64()
        val quantity = i64()
        val player = uuid()
    }

    private object Unmake : Record() {
        val tag = i8()
        val output = i64()
        val craftedBy = i64()
        val holder = i32()
        val inputs = u16()
    }

    private object UnmakeMany : Record() {
        val tag = i8()
        val craftedBy = i64()
        val holder = i32()
        val outputs = u16()
        val inputs = u16()
    }

    private object Output : Record() {
        val lot = i64()
        val holder = i32()
    }

    private object Input : Record() {
        val lot = i64()
        val quantity = i64()
    }

    fun structureStep(
        step: StructureStep,
        worldId: (WorldId) -> Int,
        blockDataId: (BlockDataKey) -> Int,
        entityTypeId: (EntityTypeKey) -> Int,
    ): ByteArray = when (step) {
        is StructureStep.SetBlock -> {
            val target = Extras.block(step.target.extras)
            val expected = Extras.block(step.expected.extras)
            Extras.check(target, expected)
            SetBlock.build(SetBlock.sized(target, expected)) {
                head(SET_BLOCK, worldId(step.at.world), step.at)
                SetBlock.target(blockDataId(step.target.data)); SetBlock.expected(blockDataId(step.expected.data))
                SetBlock.targetExtras(target); SetBlock.expectedExtras(expected)
            }
        }

        is StructureStep.SpawnEntity -> {
            val expected = step.expected
            val kind = if (expected == null) SPAWN_ENTITY else CHANGE_ENTITY
            entity(kind, step.at, step.entity, step.shape, expected, worldId, entityTypeId)
        }

        is StructureStep.RemoveEntity -> entity(REMOVE_ENTITY, step.at, step.entity, step.shape, null, worldId, entityTypeId)
    }

    /** Decodes [v] into the steps it holds, one or a whole section of them, onto [out]. */
    fun decodeStructureInto(
        v: MemorySegment,
        world: (Int) -> WorldId,
        blockData: (Int) -> BlockDataKey,
        entityType: (Int) -> EntityTypeKey,
        out: MutableList<StructureStep>,
    ) {
        if (StructHead.kind[v] != Section.STRUCT) {
            out += decodeStructureStep(v, world, blockData, entityType)
            return
        }
        val tail = Section.STRUCT_TAIL
        val at = world(StructHead.world[v])
        val x = StructHead.x[v]
        val y = StructHead.y[v]
        val z = StructHead.z[v]
        Section.forEachPosition(v, tail) { index, packed ->
            val extras = Section.extras(v, index, tail)
            out += StructureStep.SetBlock(
                BlockPos(at, x + Section.positionX(packed), y + Section.positionY(packed), z + Section.positionZ(packed)),
                BlockShape(blockData(Section.before(v, index, tail)), Extras.decodeBlock(extras?.before ?: EMPTY_BYTES)),
                BlockShape(blockData(Section.after(v, index, tail)), Extras.decodeBlock(extras?.after ?: EMPTY_BYTES)),
            )
        }
    }

    fun decodeStructureStep(
        v: MemorySegment,
        world: (Int) -> WorldId,
        blockData: (Int) -> BlockDataKey,
        entityType: (Int) -> EntityTypeKey,
    ): StructureStep {
        val at = BlockPos(world(StructHead.world[v]), StructHead.x[v], StructHead.y[v], StructHead.z[v])
        return when (val kind = StructHead.kind[v]) {
            SET_BLOCK -> StructureStep.SetBlock(
                at,
                BlockShape(blockData(SetBlock.target[v]), Extras.decodeBlock(SetBlock.targetExtras.slice(v))),
                BlockShape(blockData(SetBlock.expected[v]), Extras.decodeBlock(SetBlock.expectedExtras.slice(v))),
            )

            SPAWN_ENTITY -> StructureStep.SpawnEntity(at, Entity.uuid[v], shape(v, entityType))
            CHANGE_ENTITY -> StructureStep.SpawnEntity(at, Entity.uuid[v], shape(v, entityType), expected(v, entityType))
            REMOVE_ENTITY -> StructureStep.RemoveEntity(at, Entity.uuid[v], shape(v, entityType))
            else -> error("unrecognized structure step kind: $kind.")
        }
    }

    fun step(step: RollbackStep, holderId: (HolderId) -> Int): ByteArray = when (step) {
        is RollbackStep.Take -> Take.build {
            Take.tag(TAKE); Take.lot(step.lotId.raw); Take.quantity(step.quantity.raw); Take.holder(holderId(step.holder))
        }

        is RollbackStep.TakeRun -> TakeRun.build((TakeRun.size + 16L * step.size).toInt()) {
            TakeRun.tag(TAKE_RUN); TakeRun.holder(holderId(step.holder)); TakeRun.count(step.size)
            val quantities = TakeRun.size + 8L * step.size
            for (k in 0 until step.size) {
                v.putI64(TakeRun.size + 8L * k, step.lots[k])
                v.putI64(quantities + 8L * k, step.quantities[k])
            }
        }

        is RollbackStep.Mint -> Mint.build {
            Mint.tag(MINT); Mint.lot(step.lotId.raw); Mint.quantity(step.quantity.raw); Mint.reason(step.reason)
        }

        is RollbackStep.Debt -> Debt.build {
            Debt.tag(DEBT); Debt.lot(step.lotId.raw); Debt.quantity(step.quantity.raw); Debt.player(step.player)
        }

        is RollbackStep.Unmake -> {
            val single = step.outputs.singleOrNull()?.takeIf { it.holder == step.holder }
            if (single != null) {
                Unmake.build((Unmake.size + Input.size * step.inputs.size).toInt()) {
                    Unmake.tag(UNMAKE); Unmake.output(single.lotId.raw); Unmake.craftedBy(step.craftedBy.raw)
                    Unmake.holder(holderId(step.holder)); Unmake.inputs(step.inputs.size)
                    inputs(v, Unmake.size, step.inputs)
                }
            } else {
                val inputsAt = UnmakeMany.size + Output.size * step.outputs.size
                UnmakeMany.build((inputsAt + Input.size * step.inputs.size).toInt()) {
                    UnmakeMany.tag(UNMAKE_MANY); UnmakeMany.craftedBy(step.craftedBy.raw)
                    UnmakeMany.holder(holderId(step.holder))
                    UnmakeMany.outputs(step.outputs.size); UnmakeMany.inputs(step.inputs.size)
                    step.outputs.forEachIndexed { i, o ->
                        Output.write(v, UnmakeMany.size + Output.size * i) { Output.lot(o.lotId.raw); Output.holder(holderId(o.holder)) }
                    }
                    inputs(v, inputsAt, step.inputs)
                }
            }
        }
    }

    fun decodeStep(v: MemorySegment, holder: (Int) -> HolderId): RollbackStep = when (val kind = v.i8(0)) {
        TAKE -> RollbackStep.Take(LotId(Take.lot[v]), Quantity(Take.quantity[v]), holder(Take.holder[v]))
        TAKE_RUN -> {
            val count = TakeRun.count[v]
            val quantities = TakeRun.size + 8L * count
            RollbackStep.TakeRun(
                LongArray(count) { v.i64(TakeRun.size + 8L * it) },
                LongArray(count) { v.i64(quantities + 8L * it) },
                holder(TakeRun.holder[v]),
            )
        }

        MINT -> RollbackStep.Mint(LotId(Mint.lot[v]), Quantity(Mint.quantity[v]), Mint.reason[v])
        DEBT -> RollbackStep.Debt(LotId(Debt.lot[v]), Quantity(Debt.quantity[v]), Debt.player[v])
        UNMAKE -> {
            val at = holder(Unmake.holder[v])
            RollbackStep.Unmake(
                listOf(UnmadeOutput(LotId(Unmake.output[v]), at)),
                inputs(v, Unmake.size, Unmake.inputs[v]),
                TxnId(Unmake.craftedBy[v]),
                at,
            )
        }

        UNMAKE_MANY -> {
            val outputs = UnmakeMany.outputs[v]
            RollbackStep.Unmake(
                List(outputs) { i ->
                    val base = UnmakeMany.size + Output.size * i
                    UnmadeOutput(LotId(Output.lot[v, base]), holder(Output.holder[v, base]))
                },
                inputs(v, UnmakeMany.size + Output.size * outputs, UnmakeMany.inputs[v]),
                TxnId(UnmakeMany.craftedBy[v]),
                holder(UnmakeMany.holder[v]),
            )
        }

        else -> error("unrecognized rollback step kind: $kind.")
    }

    private fun entity(
        kind: Byte, at: BlockPos, uuid: UUID, shape: EntityShape, expected: EntityShape?,
        worldId: (WorldId) -> Int, entityTypeId: (EntityTypeKey) -> Int,
    ): ByteArray {
        val extras = Extras.entity(shape.extras)
        val tail = expected?.let { Extras.entity(it.extras) }
        Extras.check(extras, tail ?: EMPTY_BYTES)
        val bytes = Entity.sized(extras) + if (tail == null) 0 else Expected.sized(tail)
        return Entity.build(bytes) {
            head(kind, worldId(at.world), at)
            Entity.type(entityTypeId(shape.type)); Entity.uuid(uuid)
            Entity.x(shape.x); Entity.y(shape.y); Entity.z(shape.z); Entity.yaw(shape.yaw); Entity.pitch(shape.pitch)
            Entity.extras(extras)
            if (expected != null) Expected.write(v, Entity.extras.end(v)) {
                Expected.x(expected.x); Expected.y(expected.y); Expected.z(expected.z)
                Expected.yaw(expected.yaw); Expected.pitch(expected.pitch); Expected.extras(tail!!)
            }
        }
    }

    private fun Writer.head(kind: Byte, world: Int, at: BlockPos) {
        StructHead.kind(kind); StructHead.world(world); StructHead.x(at.x); StructHead.y(at.y); StructHead.z(at.z)
    }

    private fun shape(v: MemorySegment, entityType: (Int) -> EntityTypeKey) = EntityShape(
        entityType(Entity.type[v]), Entity.x[v], Entity.y[v], Entity.z[v], Entity.yaw[v], Entity.pitch[v],
        Extras.decodeEntity(Entity.extras.slice(v)),
    )

    private fun expected(v: MemorySegment, entityType: (Int) -> EntityTypeKey): EntityShape {
        val at = Entity.extras.end(v)
        return EntityShape(
            entityType(Entity.type[v]), Expected.x[v, at], Expected.y[v, at], Expected.z[v, at],
            Expected.yaw[v, at], Expected.pitch[v, at], Extras.decodeEntity(Expected.extras.slice(v, at)),
        )
    }

    private fun inputs(v: MemorySegment, at: Long, inputs: List<LotContribution>) = inputs.forEachIndexed { i, input ->
        Input.write(v, at + Input.size * i) { Input.lot(input.lotId.raw); Input.quantity(input.quantity.raw) }
    }

    private fun inputs(v: MemorySegment, at: Long, count: Int): List<LotContribution> = List(count) { i ->
        val base = at + Input.size * i
        LotContribution(LotId(Input.lot[v, base]), Quantity(Input.quantity[v, base]))
    }
}

// endregion
