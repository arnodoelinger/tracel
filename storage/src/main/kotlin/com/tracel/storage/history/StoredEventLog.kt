package com.tracel.storage.history

import com.tracel.engine.event.EventLog
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.BlockPos
import com.tracel.storage.StorageUnit
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.Cell
import com.tracel.storage.chrono.Chrono
import com.tracel.storage.chrono.Lane
import com.tracel.storage.chrono.Rows
import com.tracel.storage.chrono.Tile
import com.tracel.storage.chrono.query.Box
import com.tracel.storage.chrono.query.Found
import com.tracel.storage.chrono.query.IdTest
import com.tracel.storage.chrono.query.Sieve
import com.tracel.storage.chrono.query.and

/** The event log: what was said, what was typed, who joined and disconnected. */
class StoredEventLog(storage: TracelStorage, chrono: Chrono = Chrono.of(storage)) : AbstractChronoLog(storage, chrono), EventLog {
    override suspend fun append(event: ActorEvent) {
        storage.write {
            val byId = event.by?.let { interning.internHolder(this, it) } ?: 0
            val at = event.at
            val worldId = at?.let { interning.internWorld(this, it.world) } ?: 0
            val tile = if (at == null) Tile.of(0, 0, 0) else Tile.at(worldId, at.x, at.z)
            val writer = chrono.writer(this, Lane.EVENTS)
            check(!writer.holds(event.seq.raw) && !chrono.holds(this, tile, Lane.EVENTS, event.seq.raw)) {
                "event at ${event.seq} already appended; the log is append-only."
            }
            val entity = event.entity
            writer.addEntity(
                tile, event.seq.raw, event.epochMillis, if (at == null) 0 else Cell.of(at.x, at.y, at.z).packed, byId,
                event.kind.ordinal, 0, entity?.mostSignificantBits ?: 0L, entity?.leastSignificantBits ?: 0L,
                event.text?.toByteArray(Charsets.UTF_8), null,
            )
        }
    }

    override suspend fun query(filter: LookupFilter, kinds: Set<EventKind>): List<ActorEvent> = storage.read {
        if (kinds.isEmpty() || filter.material != null || filter.blockMaterials.isNotEmpty()) return@read emptyList()
        val holderIds = filter.holders.mapNotNull { interning.findHolderId(this, it) }
        if (filter.holders.isNotEmpty() && holderIds.isEmpty()) return@read emptyList()
        val excluded = filter.excludedHolders.mapNotNull { interning.findHolderId(this, it) }
        val entity = filter.entity
        val world = (filter.region?.world ?: filter.world)?.takeIf { entity == null }
        val worldId = world?.let { interning.findWorldId(this, it) ?: return@read emptyList() }
        val kindIds = kinds.map { it.ordinal }
        val reader = chrono.reader(this)

        val sieve = Sieve(
            since = filter.since ?: Long.MIN_VALUE,
            until = filter.until ?: Long.MAX_VALUE,
            actors = if (excluded.isEmpty()) IdTest.ANY else IdTest.none(excluded),
            kinds = IdTest.of(kindIds),
        )
        val found = ArrayList<Found>()
        val region = filter.region
        when {
            worldId != null && region != null -> {
                val tiles = ArrayList<Tile>()
                for (x in region.minTileX..region.maxTileX) for (z in region.minTileZ..region.maxTileZ) tiles += Tile.of(worldId, x, z)
                val boxed = Sieve(
                    sieve.since, sieve.until, sieve.actors, sieve.kinds, null,
                    Box(region.minX, region.maxX, region.minY, region.maxY, region.minZ, region.maxZ),
                )
                found += reader.select(tiles, boxed, Lane.EVENTS)
            }
            holderIds.isNotEmpty() && filter.holders.all { it is HolderId.Player } ->
                for (id in holderIds) found += reader.selectAnywhere(id, sieve.onlyBy(id), Lane.EVENTS)
            holderIds.isNotEmpty() -> found += reader.selectAnywhere(null, Sieve(sieve.since, sieve.until, sieve.actors and IdTest.of(holderIds), sieve.kinds), Lane.EVENTS)
            else -> found += reader.selectAnywhere(null, sieve, Lane.EVENTS)
        }
        val keep = ArrayList<Pair<Found, Int>>()
        for (f in found) {
            if (worldId != null && f.tile.world != worldId) continue
            val rows = f.rows
            for (i in 0 until rows.size) {
                if (entity != null && !(rows.uuidHigh!![i] == entity.mostSignificantBits && rows.uuidLow!![i] == entity.leastSignificantBits)) continue
                keep += f to i
            }
        }
        keep.sortWith(compareByDescending<Pair<Found, Int>> { (f, i) -> f.rows.millis[i] }.thenByDescending { (f, i) -> f.rows.seq[i] })
        keep.drop(filter.offset).take(filter.limit).map { (f, i) -> decode(this, f.tile, f.rows, i) }
    }

    private fun decode(unit: StorageUnit, tile: Tile, rows: Rows, i: Int): ActorEvent {
        val actor = rows.actor[i]
        val cell = Cell(rows.cell[i])
        return ActorEvent(
            seq = Seq(rows.seq[i]),
            kind = EventKind.entries[rows.kind[i]],
            by = if (actor == 0) null else interning.resolveHolder(unit, actor),
            epochMillis = rows.millis[i],
            at = if (tile.world == 0) null else BlockPos(interning.resolveWorld(unit, tile.world), tile.originX + cell.x, cell.y, tile.originZ + cell.z),
            text = rows.beforeExtrasAt(i)?.toString(Charsets.UTF_8),
        )
    }
}
