package com.tracel.storage.codec

import com.tracel.model.cause.CauseKind
import com.tracel.model.flow.FlowKind
import com.tracel.model.log.LogKind
import com.tracel.model.world.ActionKind
import com.tracel.platform.Versions
import com.tracel.storage.ffm.Bytes.i32
import com.tracel.storage.ffm.Bytes.i64
import com.tracel.storage.ffm.Bytes.i8
import com.tracel.storage.ffm.Bytes.putI32
import com.tracel.storage.ffm.Bytes.putI64
import com.tracel.storage.ffm.Bytes.putI8
import com.tracel.storage.ffm.Bytes.writeBytes
import java.io.ByteArrayOutputStream
import java.lang.foreign.MemorySegment
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal const val CODEC_VERSION: Byte = Versions.Format.RECORD

@PublishedApi
internal val EMPTY_BYTES: ByteArray = ByteArray(0)

internal inline fun recordBytes(size: Int, write: MemorySegment.() -> Unit): ByteArray {
    val bytes = ByteArray(size)
    MemorySegment.ofArray(bytes).write()
    return bytes
}

object Scalar {
    const val VERSION: Byte = CODEC_VERSION

    fun long(value: Long): ByteArray = recordBytes(8) { putI64(0, value) }

    fun asLong(v: MemorySegment): Long = v.i64(0)

    fun int(value: Int): ByteArray = recordBytes(4) { putI32(0, value) }

    fun asInt(v: MemorySegment): Int = v.i32(0)

    private const val PACKED: Byte = 0x7F

    fun packed(parts: List<ByteArray>): ByteArray {
        var size = 5
        for (part in parts) size += 4 + part.size
        return recordBytes(size) {
            putI8(0, PACKED)
            putI32(1, parts.size)
            var at = 5L
            for (part in parts) {
                putI32(at, part.size)
                writeBytes(at + 4, part)
                at += 4L + part.size
            }
        }
    }

    fun forEachPacked(v: MemorySegment, action: (MemorySegment) -> Unit) {
        if (v.byteSize() < 5 || v.i8(0) != PACKED) {
            action(v)
            return
        }
        val count = v.i32(1)
        var at = 5L
        repeat(count) {
            val length = v.i32(at)
            action(v.asSlice(at + 4, length.toLong()))
            at += 4L + length
        }
    }
}

/** One durability change of one tool lot. */
object Wear : Record() {
    val epochMillis = i64()
    val before = i32()
    val after = i32()

    fun mark(millis: Long, was: Int, now: Int) = build { epochMillis(millis); before(was); after(now) }
}

/** A container's full slot layout (a chest, a barrel, etc.). */
object ContainerSlot : Record() {
    val epochMillis = i64()
    val count = i32()
    private val row = rows()
    val slot = row.i32()
    val itemKeyId = row.i32()
    val quantity = row.i64()

    fun layout(millis: Long, slots: List<Triple<Int, Int, Long>>) = build(row.size(slots.size)) {
        epochMillis(millis)
        count(slots.size)
        for ((i, s) in slots.withIndex()) {
            slot(i, s.first)
            itemKeyId(i, s.second)
            quantity(i, s.third)
        }
    }
}

object LotRecord : Record() {
    val itemKeyId = i32()
    val quantity = i64()
    val createdBy = i64()

    fun of(item: Int, qty: Long, by: Long) = build { itemKeyId(item); quantity(qty); createdBy(by) }
}

object PackRecord : Record() {
    val id = i64()
    val count = i32()
    private val row = rows()
    val lot = row.i64()
    val remaining = row.i64()
    val fifo = row.i64()

    fun of(packId: Long, lots: LongArray, left: LongArray, order: LongArray, n: Int) = build(row.size(n)) {
        id(packId)
        count(n)
        for (i in 0 until n) {
            lot(i, lots[i])
            remaining(i, left[i])
            fifo(i, order[i])
        }
    }
}

object PackLocation : Record() {
    val holderId = i32()
    val itemKeyId = i32()
    val tail = i64()

    fun of(holder: Int, item: Int, fifoTail: Long) = build { holderId(holder); itemKeyId(item); tail(fifoTail) }
}

object PackSum : Record() {
    val packId = i64()
    val sum = i64()

    fun of(pack: Long, total: Long) = build { packId(pack); sum(total) }
}

object EdgeRecord : Record() {
    const val SPLIT: Byte = 0
    const val TRANSFORM: Byte = 1
    const val COMPENSATE: Byte = 2

    val kind = i8()
    val quantity = i64()
    val reference = i64()
    val producedAt = i32()

    fun of(k: Byte, qty: Long, ref: Long, at: Int) = build { kind(k); quantity(qty); reference(ref); producedAt(at) }
}

object Lease : Record() {
    val jobId = i64()
    val acquiredAt = i64()

    fun of(job: Long, millis: Long) = build { jobId(job); acquiredAt(millis) }
}

object Pending : Record() {
    val itemKeyId = i32()
    val delta = i64()
    val jobId = i64()
    val createdMillis = i64()
    private val ender = flag()

    fun of(item: Int, change: Long, job: Long, millis: Long, enderChest: Boolean) = build {
        itemKeyId(item); delta(change); jobId(job); createdMillis(millis); ender(enderChest)
    }

    fun enderChest(v: MemorySegment): Boolean = v.byteSize() >= size && ender[v]
}

object RbJob : Record() {
    val restoreTo = i32()
    val stepCount = i32()
    val createCount = i32()
    val destroyCount = i32()
    private val target = i64()
    private val hasTarget = flag()
    private val executed = i64()

    fun of(
        restore: Int,
        steps: Int,
        create: Int,
        destroy: Int,
        targetMillis: Long,
        targeted: Boolean,
        executedMillis: Long
    ) =
        build {
            restoreTo(restore); stepCount(steps); createCount(create); destroyCount(destroy)
            target(targetMillis); hasTarget(targeted); executed(executedMillis)
        }

    /** `null` for a job without a target time. Older records stop before it. */
    fun targetTime(v: MemorySegment): Long? = if (v.byteSize() < size || !hasTarget[v]) null else target[v]

    /** When the rollback ran; `0` on older records. */
    fun executedAt(v: MemorySegment): Long = if (v.byteSize() < size) 0L else executed[v]
}

object Txn : Record() {
    val version = i8()
    val cause = enum(CauseKind.entries)
    val flowCount = u16()
    val causedBy = i32()
    val id = i64()
    val epochMillis = i64()
    val world = i32()
    val x = i32()
    val y = i32()
    val z = i32()

    private val flow = rows()
    val item = flow.i32()
    val source = flow.i32()
    val destination = flow.i32()
    val kind = flow.enum(FlowKind.entries)
    private val gap = flow.pad(3)
    val quantity = flow.i64()

    fun size(flows: Int): Int = flow.size(flows)

    fun writeHeader(
        into: MemorySegment, causeKind: CauseKind, flows: Int, holder: Int, txn: Long, millis: Long,
        inWorld: Int = 0, atX: Int = 0, atY: Int = 0, atZ: Int = 0,
    ) = write(into) {
        version(CODEC_VERSION); cause(causeKind); flowCount(flows); causedBy(holder); id(txn); epochMillis(millis)
        world(inWorld); x(atX); y(atY); z(atZ)
    }

    fun writeFlow(into: MemorySegment, i: Int, itemKey: Int, from: Int, to: Int, flowKind: FlowKind, qty: Long) =
        write(into) {
            item(i, itemKey); source(i, from); destination(i, to); kind(i, flowKind); gap(i); quantity(
            i,
            qty
        )
        }
}

object Index {
    const val INLINE_NEEDS_RECORD: Byte = 1

    object Position : Record() {
        val kind = enum(LogKind.entries)
        val millis = i64()
        val cause = enum(CauseKind.entries)
        val x = i32()
        val y = i32()
        val z = i32()
    }

    object Inline : Record() {
        val kind = enum(LogKind.entries)
        val cause = enum(CauseKind.entries)
        val action = enum(ActionKind.entries)
        val flags = i8()
        val x = i32()
        val y = i32()
        val z = i32()
        val before = i32()
        val after = i32()
        val causedBy = i32()
    }

    object SectionRow : Record() {
        val kind = enum(LogKind.entries)
        val cause = enum(CauseKind.entries)
        val action = enum(ActionKind.entries)
        private val gap = pad(1)
        val x = i32()
        val y = i32()
        val z = i32()
        val count = i32()
        val causedBy = i32()

        fun of(k: LogKind, c: CauseKind, a: ActionKind, sx: Int, sy: Int, sz: Int, n: Int, by: Int) = build {
            kind(k); cause(c); action(a); gap(); x(sx); y(sy); z(sz); count(n); causedBy(by)
        }
    }

    private const val TIMED_BYTES = 10

    fun bare(k: LogKind) = Position.build(1) { Position.kind(k) }

    fun timed(k: LogKind, millis: Long, c: CauseKind) = Position.build(TIMED_BYTES) {
        Position.kind(k); Position.millis(millis); Position.cause(c)
    }

    fun at(k: LogKind, millis: Long, c: CauseKind, x: Int, y: Int, z: Int) = Position.build {
        Position.kind(k); Position.millis(millis); Position.cause(c); Position.x(x); Position.y(y); Position.z(z)
    }

    fun inline(
        k: LogKind, c: CauseKind, x: Int, y: Int, z: Int, a: ActionKind, before: Int, after: Int, by: Int, flags: Byte,
    ) = Inline.build {
        Inline.kind(k); Inline.cause(c); Inline.action(a); Inline.flags(flags)
        Inline.x(x); Inline.y(y); Inline.z(z); Inline.before(before); Inline.after(after); Inline.causedBy(by)
    }

    fun section(k: LogKind, c: CauseKind, a: ActionKind, sx: Int, sy: Int, sz: Int, n: Int, by: Int) =
        SectionRow.of(k, c, a, sx, sy, sz, n, by)

    fun kind(v: MemorySegment): LogKind = Position.kind[v]

    fun isInline(v: MemorySegment): Boolean = v.byteSize() == Inline.size
    fun isSection(v: MemorySegment): Boolean = v.byteSize() == SectionRow.size

    fun hasInline(v: MemorySegment): Boolean = isInline(v) && Inline.flags[v] != INLINE_NEEDS_RECORD
    fun action(v: MemorySegment): ActionKind = Inline.action[v]
    fun before(v: MemorySegment): Int = Inline.before[v]
    fun after(v: MemorySegment): Int = Inline.after[v]
    fun causedBy(v: MemorySegment): Int = Inline.causedBy[v]

    fun millis(v: MemorySegment): Long? =
        if (isInline(v) || isSection(v) || v.byteSize() < TIMED_BYTES - 1) null else Position.millis[v]

    fun cause(v: MemorySegment): CauseKind? = when {
        isInline(v) || isSection(v) -> Inline.cause[v]
        v.byteSize() >= TIMED_BYTES -> Position.cause[v]
        else -> null
    }

    fun x(v: MemorySegment): Int? = coordinate(v, Inline.x, Position.x)
    fun y(v: MemorySegment): Int? = coordinate(v, Inline.y, Position.y)
    fun z(v: MemorySegment): Int? = coordinate(v, Inline.z, Position.z)

    private fun coordinate(v: MemorySegment, inline: I32, position: I32): Int? = when {
        isSection(v) -> null
        isInline(v) -> inline[v]
        v.byteSize() >= Position.size -> position[v]
        else -> null
    }
}

/** Which sequence numbers of one bucket a rollback took back. */
object RolledSet {
    const val SPAN = 4096

    private const val SHIFT = 12
    private const val WORDS = SPAN / 64
    private const val HEAD = 8
    private const val BITMAP_BYTES = SPAN / 8
    private const val RUN_BYTES = 3

    fun bucketOf(seq: Long): Long = seq ushr SHIFT

    fun slotOf(seq: Long): Int = (seq and (SPAN - 1L)).toInt()

    fun millis(value: MemorySegment): Long = value.i64(0)

    /** The set in [value] as bits, one word for every 64 slots; nothing for no value. */
    fun slots(value: MemorySegment?): LongArray {
        val words = LongArray(WORDS)
        if (value == null) return words
        val body = (value.byteSize() - HEAD).toInt()
        if (body == BITMAP_BYTES) {
            for (i in 0 until WORDS) words[i] = value.i64(HEAD + i * 8L)
            return words
        }
        var at = HEAD.toLong()
        repeat(body / RUN_BYTES) {
            val packed = u8(value, at) shl 16 or (u8(value, at + 1) shl 8) or u8(value, at + 2)
            val first = packed ushr SHIFT
            for (slot in first..first + (packed and (SPAN - 1))) add(words, slot)
            at += RUN_BYTES
        }
        return words
    }

    fun add(words: LongArray, slot: Int) {
        words[slot ushr 6] = words[slot ushr 6] or (1L shl (slot and 63))
    }

    fun has(words: LongArray, slot: Int): Boolean = words[slot ushr 6] and (1L shl (slot and 63)) != 0L

    /** [words] taken back at [millis], in whichever of the two forms is smaller. */
    fun encode(millis: Long, words: LongArray): ByteArray {
        var runs = 0
        var inside = false
        for (slot in 0 until SPAN) {
            val set = has(words, slot)
            if (set && !inside) runs++
            inside = set
        }
        if (runs * RUN_BYTES >= BITMAP_BYTES) {
            return recordBytes(HEAD + BITMAP_BYTES) {
                putI64(0, millis)
                for (i in 0 until WORDS) putI64(HEAD + i * 8L, words[i])
            }
        }

        val out = ByteArray(HEAD + runs * RUN_BYTES)
        MemorySegment.ofArray(out).putI64(0, millis)
        var at = HEAD
        var slot = 0
        while (slot < SPAN) {
            if (!has(words, slot)) {
                slot++
                continue
            }
            val first = slot
            while (slot < SPAN && has(words, slot)) slot++
            val packed = first shl SHIFT or (slot - first - 1)
            out[at++] = (packed ushr 16).toByte()
            out[at++] = (packed ushr 8).toByte()
            out[at++] = packed.toByte()
        }
        return out
    }

    private fun u8(value: MemorySegment, at: Long): Int = value.i8(at).toInt() and 0xFF
}

/** `gzip` members kept inflated. */
internal object Gzipped {
    /**
     * The inflated form of [bytes], or `null` if they are not one `gzip` member that
     * deflates back byte for byte.
     */
    fun inflate(bytes: ByteArray, limit: Int): ByteArray? {
        if (bytes.size < 18 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte() || bytes[2] != 8.toByte()) return null
        val plain = runCatching {
            GZIPInputStream(bytes.inputStream()).use { input ->
                val out = ByteArrayOutputStream(bytes.size * 4)
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > limit) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        return plain.takeIf { deflate(it).contentEquals(bytes) }
    }

    /** [plain] gzipped the way the JDK does it, which is the way it came in. */
    fun deflate(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(plain.size / 3 + 32)
        GZIPOutputStream(out).use { it.write(plain) }
        return out.toByteArray()
    }
}
