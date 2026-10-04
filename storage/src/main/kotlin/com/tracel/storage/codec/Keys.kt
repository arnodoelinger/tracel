package com.tracel.storage.codec

import com.tracel.storage.spi.EngineCursor

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
 * | Tag  | Key                                                                | Value                | Scanned by            |
 * |------|--------------------------------------------------------------------|----------------------|----------------------|
 * | 01   | `txn / seq`                                                        | packed transaction   | retention, seq range |
 * | 02   | `txnById @ txnId`                                                  | seq                  | point                |
 * | 03   | `actor / holderId / ~seq`                                          | —                    | prefix, newest first |
 * | 04   | `item / itemKeyId / ~seq`                                          | —                    | prefix, newest first |
 * | 05   | `time / ~epochMillis / ~seq`                                       | —                    | range, newest first  |
 * | 06   | `spatial / worldId / chunkX / chunkZ / ~epochMillis / yBin / ~seq` | kind+time+cause+xyz  | chunk x time range   |
 * | 07   | `lot / lotId`                                                      | packed lot           | point                |
 * | 08   | retired: per-lot `place`                                           |                      |                      |
 * | 09   | retired: per-lot `placeRev`                                        |                      |                      |
 * | 0A   | retired: per-lot `placeItm`                                        |                      |                      |
 * | 0B   | `total / holderId / itemKeyId`                                     | running total        | point + prefix       |
 * | 0C   | `edgeFrom / parentLotId / childLotId`                              | packed edge          | prefix               |
 * | 0D   | `edgeInto / childLotId / parentLotId`                              | packed edge          | prefix               |
 * | 0E   | `lease / lotId`                                                    | jobId, acquiredAt    | point + full scan    |
 * | 0F   | `leaseJob / jobId / lotId`                                         | —                    | prefix               |
 * | 10   | `rbStep / jobId / stepIndex`                                       | packed plan fragment | prefix               |
 * | 11   | `rbJob / jobId`                                                    | restoreTo, stepCount | point                |
 * | 12   | `applied / kind / jobId / stepIndex`                               | —                    | point                |
 * | 13   | `pending / playerUuid / id`                                        | packed delivery      | prefix               |
 * | 14   | `counter / nameId`                                                 | next value           | point                |
 * | 15   | `internFw / namespace / id`                                        | packed value         | poin                 |
 * | 16   | `internRv / namespace / packed value`                              | id                   | pot                  |
 * | 17   | `wchg / seq`                                                       | packed world change  | retention, seq range |
 * | 18   | `wchgAt / worldId / x / y / z / ~seq`                              | seq                  | prefix, newest first |
 * | 19   | `wchgEnt / entityUuid / ~seq`                                      | seq                  | prefix, newest first |
 * | 1A   | `txnLot / seq / flowIndex / lotId`                                 | quantity             | prefix               |
 * | 1B   | `blockLease / worldId / x / y / z`                                 | jobId, acquiredAt    | point + full scan    |
 * | 1C   | `rbStruct / jobId / stepIndex`                                     | packed structure step| prefix               |
 * | 1D   | `rbTarget / jobId / rootLotId`                                     | holderId             | prefix               |
 * | 21   | `cslot / holderId / ~seq`                                          | packed slot layout   | prefix, newest first |
 * | 23   | `wear / lotId / seq`                                               | epochMillis, damage  | prefix, oldest first |
 * | 24   | `leaseSet / jobId`                                                 | acquiredAt, lotIds   | full scan on open    |
 * | 25   | `pack / packId`                                                    | holderId, item, tail | point                |
 * | 26   | `packAt / holderId / itemKeyId / tailFifoSeq`                      | the pack's lots      | prefix, FIFO order   |
 * | 27   | `packItm / itemKeyId / holderId / tailFifoSeq`                     | packId, sum          | prefix (census)      |
 * | 28   | `lotPack / lotId`                                                  | packId               | point                |
 * | 29   | `actorKind / holderId`                                             | entityTypeId         | point                |
 * | 2A   | `actorMode / holderId / ~epochMillis`                              | game mode code       | prefix, newest first |
 * | 2B   | `actorVisit / holderId / ~openedAt`                                | closedAt (or open)   | prefix, newest first |
 * | 2C   | `importMark / sourceId`                                            | last rows, records   | point                |
 * | 2D   | `evt / seq`                                                        | packed event         | retention            |
 * | 2E   | `evtActor / holderId / ~epochMillis / ~seq`                        | —                    | range, newest first  |
 * | 2F   | `evtTime / ~epochMillis / ~seq`                                    | —                    | range, newest first  |
 * | 30   | `rolled / seq`                                                     | jobId, epochMillis   | point                |
 * | 31   | `rolledJob / jobId / seq`                                          | —                    | prefix               |
 * | 32   | `meta / 0`                                                         | format major, minor  | point                |
 */
object Keys {
    fun tagName(tag: Byte): String = when (tag) {
        TXN -> "txn"
        TXN_BY_ID -> "txnById"
        ACTOR -> "actor"
        ITEM -> "item"
        TIME -> "time"
        SPATIAL -> "spatial"
        LOT -> "lot"
        PLACE -> "place"
        PLACE_REV -> "placeRev"
        PLACE_ITEM -> "placeItm"
        TOTAL -> "total"
        EDGE_FROM -> "edgeFrom"
        EDGE_INTO -> "edgeInto"
        LEASE -> "lease"
        LEASE_JOB -> "leaseJob"
        RB_STEP -> "rbStep"
        RB_JOB -> "rbJob"
        APPLIED -> "applied"
        PENDING -> "pending"
        COUNTER -> "counter"
        INTERN_FORWARD -> "internFw"
        INTERN_REVERSE -> "internRv"
        WCHG -> "wchg"
        WCHG_AT -> "wchgAt"
        WCHG_AT_SECTION -> "wchgSec"
        WCHG_ENTITY -> "wchgEnt"
        TXN_LOT -> "txnLot"
        BLOCK_LEASE -> "blockLease"
        RB_STRUCT -> "rbStruct"
        RB_TARGET -> "rbTarget"
        ITEM_FORM -> "itemForm"
        CONTAINER_SLOT -> "cslot"
        WEAR -> "wear"
        LEASE_SET -> "leaseSet"
        PACK -> "pack"
        PACK_AT -> "packAt"
        PACK_ITEM -> "packItm"
        LOT_PACK -> "lotPack"
        ACTOR_KIND -> "actorKind"
        ACTOR_MODE -> "actorMode"
        ACTOR_VISIT -> "actorVisit"
        IMPORT_MARK -> "importMark"
        EVENT -> "evt"
        EVENT_ACTOR -> "evtActor"
        EVENT_TIME -> "evtTime"
        ROLLED -> "rolled"
        ROLLED_JOB -> "rolledJob"
        META -> "meta"
        else -> "tag%02x".format(tag.toInt() and 0xff)
    }

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
    const val WCHG: Byte = 0x17
    const val WCHG_AT: Byte = 0x18
    const val WCHG_ENTITY: Byte = 0x19
    const val WCHG_AT_SECTION: Byte = 0x20
    const val TXN_LOT: Byte = 0x1A
    const val BLOCK_LEASE: Byte = 0x1B
    const val RB_STRUCT: Byte = 0x1C
    const val RB_TARGET: Byte = 0x1D
    const val RB_RECENT: Byte = 0x1E
    const val ITEM_FORM: Byte = 0x1F
    const val CONTAINER_SLOT: Byte = 0x21
    const val GROUND_AT: Byte = 0x22
    const val WEAR: Byte = 0x23
    const val LEASE_SET: Byte = 0x24
    const val PACK: Byte = 0x25
    const val PACK_AT: Byte = 0x26
    const val PACK_ITEM: Byte = 0x27
    const val LOT_PACK: Byte = 0x28
    const val ACTOR_KIND: Byte = 0x29
    const val ACTOR_MODE: Byte = 0x2A
    const val ACTOR_VISIT: Byte = 0x2B
    const val IMPORT_MARK: Byte = 0x2C
    const val EVENT: Byte = 0x2D
    const val EVENT_ACTOR: Byte = 0x2E
    const val EVENT_TIME: Byte = 0x2F
    const val ROLLED: Byte = 0x30
    const val ROLLED_JOB: Byte = 0x31
    const val META: Byte = 0x32
    const val PROGRESS_ROLLBACK: Byte = 0
    const val PROGRESS_INVOLUTION: Byte = 1
    const val NS_ITEM_KEY: Byte = 0
    const val NS_HOLDER: Byte = 1
    const val NS_WORLD: Byte = 2
    const val NS_BLOCK_DATA: Byte = 3
    const val NS_ENTITY_TYPE: Byte = 4

    // region Functions

    fun invert(sequence: Long): Long = Long.MAX_VALUE - sequence

    fun ordered(value: Int): Int = value xor Int.MIN_VALUE

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

    const val SPATIAL_SIZE: Int = 30

    fun yBin(y: Int): Byte = (((y shr 4) + 64) and 0xFF).toByte()

    fun spatial(worldId: Int, chunkX: Int, chunkZ: Int, y: Int, seq: Long, epochMillis: Long): ByteArray =
        KeyWriter(SPATIAL_SIZE).tag(SPATIAL).u32(worldId)
            .u32(ordered(chunkX)).u32(ordered(chunkZ)).u64(invert(epochMillis)).u8(yBin(y))
            .u64(invert(seq)).done()

    fun spatialColumnPrefix(worldId: Int, chunkX: Int): ByteArray =
        KeyWriter(9).tag(SPATIAL).u32(worldId).u32(ordered(chunkX)).done()

    fun spatialChunkPrefix(worldId: Int, chunkX: Int, chunkZ: Int): ByteArray =
        KeyWriter(13).tag(SPATIAL).u32(worldId).u32(ordered(chunkX)).u32(ordered(chunkZ)).done()

    fun spatialChunkFrom(worldId: Int, chunkX: Int, chunkZ: Int, untilMillis: Long?): ByteArray {
        if (untilMillis == null) return spatialChunkPrefix(worldId, chunkX, chunkZ)
        return KeyWriter(21).tag(SPATIAL).u32(worldId).u32(ordered(chunkX)).u32(ordered(chunkZ))
            .u64(invert(untilMillis)).done()
    }

    fun spatialChunkX(key: ByteArray): Int = KeyReader.u32(key, 5) xor Int.MIN_VALUE

    fun spatialChunkZ(key: ByteArray): Int = KeyReader.u32(key, 9) xor Int.MIN_VALUE

    fun spatialMillis(key: ByteArray): Long = invert(KeyReader.u64(key, 13))

    fun spatialChunkZ(cursor: EngineCursor): Int = cursor.keyU32(9) xor Int.MIN_VALUE

    fun spatialMillis(cursor: EngineCursor): Long = invert(cursor.keyU64(13))

    fun lot(lotId: Long): ByteArray = KeyWriter(9).tag(LOT).u64(lotId).done()

    fun pack(packId: Long): ByteArray = KeyWriter(9).tag(PACK).u64(packId).done()

    fun packAt(holderId: Int, itemKeyId: Int, tailFifoSeq: Long): ByteArray =
        KeyWriter(17).tag(PACK_AT).u32(holderId).u32(itemKeyId).u64(tailFifoSeq).done()

    fun packAtPrefix(holderId: Int, itemKeyId: Int): ByteArray =
        KeyWriter(9).tag(PACK_AT).u32(holderId).u32(itemKeyId).done()

    fun packAtHolderPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(PACK_AT).u32(holderId).done()

    fun packItem(itemKeyId: Int, holderId: Int, tailFifoSeq: Long): ByteArray =
        KeyWriter(17).tag(PACK_ITEM).u32(itemKeyId).u32(holderId).u64(tailFifoSeq).done()

    fun packItemPrefix(itemKeyId: Int): ByteArray = KeyWriter(5).tag(PACK_ITEM).u32(itemKeyId).done()

    fun lotPack(lotId: Long): ByteArray = KeyWriter(9).tag(LOT_PACK).u64(lotId).done()

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

    fun leaseSet(jobId: Long): ByteArray = KeyWriter(9).tag(LEASE_SET).u64(jobId).done()

    fun rbStep(jobId: Long, stepIndex: Int): ByteArray =
        KeyWriter(13).tag(RB_STEP).u64(jobId).u32(stepIndex).done()

    fun rbStepPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(RB_STEP).u64(jobId).done()

    fun rbJob(jobId: Long): ByteArray = KeyWriter(9).tag(RB_JOB).u64(jobId).done()

    fun applied(kind: Byte, jobId: Long, stepIndex: Int): ByteArray =
        KeyWriter(14).tag(APPLIED).u8(kind).u64(jobId).u32(stepIndex).done()

    fun appliedPrefix(kind: Byte, jobId: Long): ByteArray =
        KeyWriter(10).tag(APPLIED).u8(kind).u64(jobId).done()

    fun pending(player: java.util.UUID, id: Long): ByteArray =
        KeyWriter(25).tag(PENDING).u64(player.mostSignificantBits).u64(player.leastSignificantBits).u64(id).done()

    fun pendingPrefix(player: java.util.UUID): ByteArray =
        KeyWriter(17).tag(PENDING).u64(player.mostSignificantBits).u64(player.leastSignificantBits).done()

    fun counter(nameId: Int): ByteArray = KeyWriter(5).tag(COUNTER).u32(nameId).done()

    fun internForward(namespace: Byte, id: Int): ByteArray =
        KeyWriter(6).tag(INTERN_FORWARD).u8(namespace).u32(id).done()

    fun internReverse(namespace: Byte, packed: ByteArray): ByteArray =
        KeyWriter(2 + packed.size).tag(INTERN_REVERSE).u8(namespace).raw(packed).done()

    fun wchg(seq: Long): ByteArray = KeyWriter(9).tag(WCHG).u64(seq).done()

    fun wchgAt(worldId: Int, x: Int, y: Int, z: Int, seq: Long): ByteArray =
        KeyWriter(25).tag(WCHG_AT).u32(worldId).u32(ordered(x)).u32(ordered(y)).u32(ordered(z))
            .u64(invert(seq)).done()

    fun wchgAtPrefix(worldId: Int, x: Int, y: Int, z: Int): ByteArray =
        KeyWriter(17).tag(WCHG_AT).u32(worldId).u32(ordered(x)).u32(ordered(y)).u32(ordered(z)).done()

    fun wchgAtSection(worldId: Int, sectionX: Int, sectionY: Int, sectionZ: Int, seq: Long): ByteArray =
        KeyWriter(25).tag(WCHG_AT_SECTION).u32(worldId)
            .u32(ordered(sectionX)).u32(ordered(sectionY)).u32(ordered(sectionZ))
            .u64(invert(seq)).done()

    fun wchgAtSectionPrefix(worldId: Int, sectionX: Int, sectionY: Int, sectionZ: Int): ByteArray =
        KeyWriter(17).tag(WCHG_AT_SECTION).u32(worldId)
            .u32(ordered(sectionX)).u32(ordered(sectionY)).u32(ordered(sectionZ)).done()

    fun wchgEntity(entity: java.util.UUID, seq: Long): ByteArray =
        KeyWriter(25).tag(WCHG_ENTITY).u64(entity.mostSignificantBits).u64(entity.leastSignificantBits)
            .u64(invert(seq)).done()

    fun txnLot(seq: Long, flowIndex: Int, lotId: Long): ByteArray =
        KeyWriter(21).tag(TXN_LOT).u64(seq).u32(flowIndex).u64(lotId).done()

    fun txnLotPrefix(seq: Long): ByteArray = KeyWriter(9).tag(TXN_LOT).u64(seq).done()

    fun blockLease(worldId: Int, x: Int, y: Int, z: Int): ByteArray =
        KeyWriter(17).tag(BLOCK_LEASE).u32(worldId).u32(ordered(x)).u32(ordered(y)).u32(ordered(z)).done()

    fun rbStruct(jobId: Long, stepIndex: Int): ByteArray =
        KeyWriter(13).tag(RB_STRUCT).u64(jobId).u32(stepIndex).done()

    fun rbStructPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(RB_STRUCT).u64(jobId).done()

    fun rbTarget(jobId: Long, rootLotId: Long): ByteArray =
        KeyWriter(17).tag(RB_TARGET).u64(jobId).u64(rootLotId).done()

    fun rbTargetPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(RB_TARGET).u64(jobId).done()

    fun cslot(holderId: Int, seq: Long): ByteArray =
        KeyWriter(13).tag(CONTAINER_SLOT).u32(holderId).u64(invert(seq)).done()

    fun cslotPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(CONTAINER_SLOT).u32(holderId).done()

    fun wear(lotId: Long, seq: Long): ByteArray = KeyWriter(17).tag(WEAR).u64(lotId).u64(seq).done()

    fun wearPrefix(lotId: Long): ByteArray = KeyWriter(9).tag(WEAR).u64(lotId).done()

    fun groundAt(itemEntityId: Int): ByteArray = KeyWriter(5).tag(GROUND_AT).u32(itemEntityId).done()

    fun actorKind(holderId: Int): ByteArray = KeyWriter(5).tag(ACTOR_KIND).u32(holderId).done()

    fun actorMode(holderId: Int, epochMillis: Long): ByteArray =
        KeyWriter(13).tag(ACTOR_MODE).u32(holderId).u64(invert(epochMillis)).done()

    fun actorModePrefix(holderId: Int): ByteArray = KeyWriter(5).tag(ACTOR_MODE).u32(holderId).done()

    fun actorVisit(holderId: Int, openedAt: Long): ByteArray =
        KeyWriter(13).tag(ACTOR_VISIT).u32(holderId).u64(invert(openedAt)).done()

    fun actorVisitPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(ACTOR_VISIT).u32(holderId).done()

    fun importMark(sourceId: Long): ByteArray = KeyWriter(9).tag(IMPORT_MARK).u64(sourceId).done()

    fun formatVersion(): ByteArray = KeyWriter(2).tag(META).u8(0).done()

    fun rolled(seq: Long): ByteArray = KeyWriter(9).tag(ROLLED).u64(seq).done()

    fun rolledJob(jobId: Long, seq: Long): ByteArray = KeyWriter(17).tag(ROLLED_JOB).u64(jobId).u64(seq).done()

    fun rolledJobPrefix(jobId: Long): ByteArray = KeyWriter(9).tag(ROLLED_JOB).u64(jobId).done()

    fun event(seq: Long): ByteArray = KeyWriter(9).tag(EVENT).u64(seq).done()

    fun eventActor(holderId: Int, epochMillis: Long, seq: Long): ByteArray =
        KeyWriter(21).tag(EVENT_ACTOR).u32(holderId).u64(invert(epochMillis)).u64(invert(seq)).done()

    fun eventActorPrefix(holderId: Int): ByteArray = KeyWriter(5).tag(EVENT_ACTOR).u32(holderId).done()

    fun eventActorFrom(holderId: Int, untilMillis: Long): ByteArray =
        KeyWriter(13).tag(EVENT_ACTOR).u32(holderId).u64(invert(untilMillis)).done()

    fun eventTime(epochMillis: Long, seq: Long): ByteArray =
        KeyWriter(17).tag(EVENT_TIME).u64(invert(epochMillis)).u64(invert(seq)).done()

    fun eventTimeFrom(untilMillis: Long): ByteArray = KeyWriter(9).tag(EVENT_TIME).u64(invert(untilMillis)).done()

    fun rbRecent(jobId: Long): ByteArray = KeyWriter(9).tag(RB_RECENT).u64(invert(jobId)).done()

    fun rbRecentPrefix(): ByteArray = KeyWriter(1).tag(RB_RECENT).done()

    fun itemForm(digest: ByteArray): ByteArray = KeyWriter(1 + digest.size).tag(ITEM_FORM).raw(digest).done()

    /** A mob's type hangs on its holder id and a player's current mode is state, not history: both outlive a purge. */
    val KEEPS_ITS_NUMBERING: ByteArray =
        byteArrayOf(COUNTER, INTERN_FORWARD, INTERN_REVERSE, ITEM_FORM, ACTOR_KIND, ACTOR_MODE, META)

    fun tagPrefix(tag: Byte): ByteArray = byteArrayOf(tag)

    // endregion

    val ALL: ByteArray = byteArrayOf(
        TXN,
        TXN_BY_ID,
        ACTOR,
        ITEM,
        TIME,
        SPATIAL,
        LOT,
        PLACE,
        PLACE_REV,
        PLACE_ITEM,
        TOTAL,
        EDGE_FROM,
        EDGE_INTO,
        LEASE,
        LEASE_JOB,
        RB_STEP,
        RB_JOB,
        APPLIED,
        PENDING,
        COUNTER,
        INTERN_FORWARD,
        INTERN_REVERSE,
        WCHG,
        WCHG_AT,
        WCHG_AT_SECTION,
        WCHG_ENTITY,
        TXN_LOT,
        BLOCK_LEASE,
        RB_STRUCT,
        RB_TARGET,
        RB_RECENT,
        ITEM_FORM,
        CONTAINER_SLOT,
        GROUND_AT,
        WEAR,
        LEASE_SET,
        PACK,
        PACK_AT,
        PACK_ITEM,
        LOT_PACK,
        ACTOR_KIND,
        ACTOR_MODE,
        ACTOR_VISIT,
        IMPORT_MARK,
        EVENT,
        EVENT_ACTOR,
        EVENT_TIME,
        ROLLED,
        ROLLED_JOB,
        META,
    )
}

// region Key writer & reader

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

// endregion
