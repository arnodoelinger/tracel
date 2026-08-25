package com.tracel.storage.codec

/**
 * The whole keyspace.
 *
 * Two rules hold everywhere and everything else follows from them:
 *
 * 1. Keys are big-endian, values are little-endian. Keys are compared as unsigned bytes, so
 *    only big-endian puts numbers in numeric order; values are never compared, so they are
 *    written in the machine's own order and a load is a load.
 * 2. Every sequence number in a secondary index is stored inverted ([invert]).
 *
 * | Tag  | Key                                                | Value                | Scanned by              |
 * |------|----------------------------------------------------|----------------------|-------------------------|
 * | 01   | `txn / seq`                                        | packed transaction   | retention, seq range    |
 * | 02   | `txnById @ txnId`                                  | seq                  | point                   |
 * | 03   | `actor / holderId / ~seq`                          | —                    | prefix, newest first    |
 * | 04   | `item / itemKeyId / ~seq`                          | —                    | prefix, newest first    |
 * | 05   | `time / ~epochMillis / ~seq`                       | —                    | range, newest first     |
 * | 06   | `spatial / worldId / chunkX / chunkZ / yBin / ~seq`| —                    | chunk rectangle         |
 * | 07   | `lot / lotId`                                      | packed lot           | point                   |
 * | 08   | `place / holderId / itemKeyId / fifoSeq`           | lotId, remaining     | prefix, FIFO order      |
 * | 09   | `placeRev / lotId / holderId`                      | itemKeyId, fifoSeq   | point                   |
 * | 0A   | `placeItm / itemKeyId / holderId / fifoSeq`        | —                    | prefix (census)         |
 * | 0B   | `total / holderId / itemKeyId`                     | running total        | point + prefix          |
 * | 0C   | `edgeFrom / parentLotId / childLotId`              | packed edge          | prefix                  |
 * | 0D   | `edgeInto / childLotId / parentLotId`              | packed edge          | prefix                  |
 * | 0E   | `lease / lotId`                                    | jobId, acquiredAt    | point + full scan       |
 * | 0F   | `leaseJob / jobId / lotId`                         | —                    | prefix                  |
 * | 10   | `rbStep / jobId / stepIndex`                       | packed plan fragment | prefix                  |
 * | 11   | `rbJob / jobId`                                    | restoreTo, stepCount | point                   |
 * | 12   | `applied / kind / jobId / stepIndex`               | —                    | point                   |
 * | 13   | `pending / playerUuid / id`                        | packed delivery      | prefix                  |
 * | 14   | `counter / nameId`                                 | next value           | point                   |
 * | 15   | `internFw / namespace / id`                        | packed value         | point                   |
 * | 16   | `internRv / namespace / packed value`              | id                   | point                   |
 *
 * The `total` family is the spec's `latest` index under a name that says what it holds: it is a
 * pure cache of what summing a [PLACE] prefix would produce.
 */
object Keys {
    const val TXN: Byte = 0x01
    const val TXN_BY_ID: Byte = 0x02
    const val ACTOR: Byte = 0x03
    const val ITEM: Byte = 0x04
    const val TIME: Byte = 0x05
    const val SPATIAL: Byte = 0x06
    const val LOT: Byte = 0x07
    const val PLACE: Byte = 0x08
    const val PLACE_REV: Byte = 0x09
    const val PLACE_ITEM: Byte = 0x0A
    const val TOTAL: Byte = 0x0B
    const val EDGE_FROM: Byte = 0x0C
    const val EDGE_INTO: Byte = 0x0D
    const val LEASE: Byte = 0x0E
    const val LEASE_JOB: Byte = 0x0F
    const val RB_STEP: Byte = 0x10
    const val RB_JOB: Byte = 0x11
    const val APPLIED: Byte = 0x12
    const val PENDING: Byte = 0x13
    const val COUNTER: Byte = 0x14
    const val INTERN_FORWARD: Byte = 0x15
    const val INTERN_REVERSE: Byte = 0x16
    const val PROGRESS_ROLLBACK: Byte = 0
    const val PROGRESS_INVOLUTION: Byte = 1
    const val NS_ITEM_KEY: Byte = 0
    const val NS_HOLDER: Byte = 1
    const val NS_WORLD: Byte = 2

    fun invert(sequence: Long): Long = Long.MAX_VALUE - sequence

    fun ordered(value: Int): Int = value xor Int.MIN_VALUE

    fun yBin(y: Int): Byte = (((y shr 4) + 64) and 0xFF).toByte()

    fun txn(seq: Long): ByteArray = KeyWriter(9).tag(TXN).u64(seq).done()

    fun txnById(txnId: Long): ByteArray = KeyWriter(9).tag(TXN_BY_ID).u64(txnId).done()

    fun actor(holderId: Int, seq: Long): ByteArray =
        KeyWriter(13).tag(ACTOR).u32(holderId).u64(invert(seq)).done()

    fun actorPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(ACTOR).u32(holderId).done()

    fun item(itemKeyId: Int, seq: Long): ByteArray =
        KeyWriter(13).tag(ITEM).u32(itemKeyId).u64(invert(seq)).done()

    fun itemPrefix(itemKeyId: Int): ByteArray = KeyWriter(5).tag(ITEM).u32(itemKeyId).done()

    fun time(epochMillis: Long, seq: Long): ByteArray =
        KeyWriter(17).tag(TIME).u64(invert(epochMillis)).u64(invert(seq)).done()

    fun timeFrom(untilMillis: Long): ByteArray = KeyWriter(9).tag(TIME).u64(invert(untilMillis)).done()

    fun spatial(worldId: Int, chunkX: Int, chunkZ: Int, y: Int, seq: Long): ByteArray =
        KeyWriter(22).tag(SPATIAL).u32(worldId).u32(ordered(chunkX)).u32(ordered(chunkZ))
            .u8(yBin(y)).u64(invert(seq)).done()

    fun spatialChunkPrefix(worldId: Int, chunkX: Int, chunkZ: Int): ByteArray =
        KeyWriter(13).tag(SPATIAL).u32(worldId).u32(ordered(chunkX)).u32(ordered(chunkZ)).done()

    fun lot(lotId: Long): ByteArray = KeyWriter(9).tag(LOT).u64(lotId).done()

    fun place(holderId: Int, itemKeyId: Int, fifoSeq: Long): ByteArray =
        KeyWriter(17).tag(PLACE).u32(holderId).u32(itemKeyId).u64(fifoSeq).done()

    fun placePrefix(holderId: Int, itemKeyId: Int): ByteArray =
        KeyWriter(9).tag(PLACE).u32(holderId).u32(itemKeyId).done()

    fun placeHolderPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(PLACE).u32(holderId).done()

    fun placeRev(lotId: Long, holderId: Int): ByteArray =
        KeyWriter(13).tag(PLACE_REV).u64(lotId).u32(holderId).done()

    fun placeRevPrefix(lotId: Long): ByteArray = KeyWriter(9).tag(PLACE_REV).u64(lotId).done()

    fun placeItem(itemKeyId: Int, holderId: Int, fifoSeq: Long): ByteArray =
        KeyWriter(17).tag(PLACE_ITEM).u32(itemKeyId).u32(holderId).u64(fifoSeq).done()

    fun placeItemPrefix(itemKeyId: Int): ByteArray = KeyWriter(5).tag(PLACE_ITEM).u32(itemKeyId).done()

    fun total(holderId: Int, itemKeyId: Int): ByteArray =
        KeyWriter(9).tag(TOTAL).u32(holderId).u32(itemKeyId).done()

    fun totalPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(TOTAL).u32(holderId).done()

    fun edgeFrom(parentLotId: Long, childLotId: Long): ByteArray =
        KeyWriter(17).tag(EDGE_FROM).u64(parentLotId).u64(childLotId).done()

    fun edgeFromPrefix(parentLotId: Long): ByteArray = KeyWriter(9).tag(EDGE_FROM).u64(parentLotId).done()

    fun edgeInto(childLotId: Long, parentLotId: Long): ByteArray =
        KeyWriter(17).tag(EDGE_INTO).u64(childLotId).u64(parentLotId).done()

    fun edgeIntoPrefix(childLotId: Long): ByteArray = KeyWriter(9).tag(EDGE_INTO).u64(childLotId).done()

    fun lease(lotId: Long): ByteArray = KeyWriter(9).tag(LEASE).u64(lotId).done()

    fun leasePrefix(): ByteArray = KeyWriter(1).tag(LEASE).done()

    fun leaseJob(jobId: Long, lotId: Long): ByteArray =
        KeyWriter(17).tag(LEASE_JOB).u64(jobId).u64(lotId).done()

    fun leaseJobPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(LEASE_JOB).u64(jobId).done()

    fun rbStep(jobId: Long, stepIndex: Int): ByteArray =
        KeyWriter(13).tag(RB_STEP).u64(jobId).u32(stepIndex).done()

    fun rbStepPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(RB_STEP).u64(jobId).done()

    fun rbJob(jobId: Long): ByteArray = KeyWriter(9).tag(RB_JOB).u64(jobId).done()

    fun applied(kind: Byte, jobId: Long, stepIndex: Int): ByteArray =
        KeyWriter(14).tag(APPLIED).u8(kind).u64(jobId).u32(stepIndex).done()

    fun pending(player: java.util.UUID, id: Long): ByteArray =
        KeyWriter(25).tag(PENDING).u64(player.mostSignificantBits).u64(player.leastSignificantBits).u64(id).done()

    fun pendingPrefix(player: java.util.UUID): ByteArray =
        KeyWriter(17).tag(PENDING).u64(player.mostSignificantBits).u64(player.leastSignificantBits).done()

    fun counter(nameId: Int): ByteArray = KeyWriter(5).tag(COUNTER).u32(nameId).done()

    fun internForward(namespace: Byte, id: Int): ByteArray =
        KeyWriter(6).tag(INTERN_FORWARD).u8(namespace).u32(id).done()

    fun internReverse(namespace: Byte, packed: ByteArray): ByteArray =
        KeyWriter(2 + packed.size).tag(INTERN_REVERSE).u8(namespace).raw(packed).done()

    fun tagPrefix(tag: Byte): ByteArray = byteArrayOf(tag)
}

/** Fixed-size big-endian key builder. Sized exactly, so it never grows and never copies. */
class KeyWriter(size: Int) {
    private val bytes = ByteArray(size)
    private var at = 0

    fun tag(value: Byte): KeyWriter = u8(value)

    fun u8(value: Byte): KeyWriter {
        bytes[at++] = value
        return this
    }

    fun u32(value: Int): KeyWriter {
        bytes[at++] = (value ushr 24).toByte()
        bytes[at++] = (value ushr 16).toByte()
        bytes[at++] = (value ushr 8).toByte()
        bytes[at++] = value.toByte()
        return this
    }

    fun u64(value: Long): KeyWriter {
        for (shift in 56 downTo 0 step 8) bytes[at++] = (value ushr shift).toByte()
        return this
    }

    fun raw(value: ByteArray): KeyWriter {
        value.copyInto(bytes, at)
        at += value.size
        return this
    }

    fun done(): ByteArray {
        check(at == bytes.size) { "key writer sized ${bytes.size} but wrote $at bytes" }
        return bytes
    }
}

/** Reads back what [KeyWriter] wrote. */
object KeyReader {
    fun u32(key: ByteArray, at: Int): Int =
        ((key[at].toInt() and 0xFF) shl 24) or ((key[at + 1].toInt() and 0xFF) shl 16) or
            ((key[at + 2].toInt() and 0xFF) shl 8) or (key[at + 3].toInt() and 0xFF)

    fun u64(key: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (key[at + i].toLong() and 0xFF)
        return value
    }
}
