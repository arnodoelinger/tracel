package com.tracel.storage.chrono

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.holder.HolderId
import com.tracel.storage.StorageUnit
import com.tracel.storage.chrono.query.Box
import com.tracel.storage.chrono.query.ChronoReader
import com.tracel.storage.chrono.query.Found
import com.tracel.storage.chrono.query.IdTest
import com.tracel.storage.chrono.query.Sieve
import com.tracel.storage.chrono.query.and
import com.tracel.storage.history.WorldFilter
import com.tracel.storage.history.idsFor
import com.tracel.storage.Interning
import java.util.UUID

/**
 * A [LookupFilter] in chrono's terms: the sieve, and where to look — the tiles of a region, the capsules of some
 * actors, or everything newest first.
 *
 * Rollback bookkeeping is never lookup history. A material names blocks, so a filter with one leaves entities out.
 */
internal class LookupPlan private constructor(
    private val sieve: Sieve,
    private val tiles: List<Tile>?,
    private val actors: List<Int>?,
    private val world: Int?,
    private val entities: Boolean,
    private val entity: UUID?,
) {
    fun run(reader: ChronoReader, ends: Boolean): List<Pair<Lane, Found>> {
        val out = ArrayList<Pair<Lane, Found>>()
        for (lane in Lane.WORLD) {
            if (lane == Lane.ENTITIES && !entities) continue
            if (lane == Lane.BLOCKS && entity != null) continue
            val found = when {
                tiles != null -> reader.select(tiles, sieve, lane)
                actors != null -> actors.flatMap { a -> reader.selectAnywhere(a, sieve.onlyBy(a), lane) }
                else -> reader.selectAnywhere(null, sieve, lane)
            }.filter { world == null || it.tile.world == world }.let { byEntity(it) }
            val kept = if (ends) reader.endsOf(mergeTiles(found), lane) else found
            for (f in kept) out += lane to f
        }
        return out
    }

    private fun byEntity(found: List<Found>): List<Found> {
        val wanted = entity ?: return found
        return found.mapNotNull { f ->
            val high = f.rows.uuidHigh ?: return@mapNotNull null
            val low = f.rows.uuidLow ?: return@mapNotNull null
            val keep = IntArray(f.rows.size)
            var n = 0
            for (r in 0 until f.rows.size) {
                keep[n] = r
                n += if (high[r] == wanted.mostSignificantBits && low[r] == wanted.leastSignificantBits) 1 else 0
            }
            if (n == 0) null else Found(f.tile, f.rows.pick(keep, n))
        }
    }

    private fun mergeTiles(found: List<Found>): List<Found> {
        if (found.size < 2) return found
        return found.groupBy { it.tile.packed }.map { (packed, parts) ->
            if (parts.size == 1) parts[0] else Found(Tile(packed), Rows.concat(parts.map { it.rows }).sorted())
        }
    }

    companion object {
        fun of(unit: StorageUnit, interning: Interning, filter: LookupFilter): LookupPlan? {
            val ids = interning.idsFor(unit, filter)
            if (ids.matchesNothing) return null
            val causes = filter.worldCauses ?: filter.causes
            val kinds = IdTest { kind ->
                val cause = ChronoKinds.cause(kind)
                !cause.isBookkeeping &&
                    (causes.isEmpty() || cause in causes) &&
                    (filter.excludedCauses.isEmpty() || cause !in filter.excludedCauses) &&
                    (filter.actions.isEmpty() || ChronoKinds.action(kind) in filter.actions)
            }
            var actors = if (ids.holders.isNotEmpty()) IdTest.of(ids.holders) else IdTest.ANY
            if (ids.excluded.isNotEmpty()) actors = actors and IdTest.none(ids.excluded)
            val material = filter.material
            val states = if (material == null) null else {
                val memo = HashMap<Int, Boolean>()
                IdTest { id ->
                    memo.getOrPut(id) { WorldFilter.matches(interning.resolveBlockData(unit, id).value, material, filter.blockMaterials) }
                }
            }
            val region = filter.region
            val place = region?.let { placeOf(unit, interning, it) ?: return null }
            val sieve = Sieve(
                since = filter.since ?: Long.MIN_VALUE,
                until = filter.until ?: Long.MAX_VALUE,
                actors = actors,
                kinds = kinds,
                states = states,
                box = place?.second ?: Box.EVERYWHERE,
            )
            return LookupPlan(
                sieve,
                tiles = place?.first,
                actors = if (place == null && ids.holders.isNotEmpty() && filter.holders.all { it is HolderId.Player }) ids.holders.toList() else null,
                world = ids.world,
                entities = material == null,
                entity = filter.entity,
            )
        }

        fun placeOf(unit: StorageUnit, interning: Interning, region: LookupRegion): Pair<List<Tile>, Box>? {
            val world = interning.findWorldId(unit, region.world) ?: return null
            val tiles = ArrayList<Tile>()
            for (x in region.minTileX..region.maxTileX) for (z in region.minTileZ..region.maxTileZ) tiles += Tile.of(world, x, z)
            return tiles to Box(region.minX, region.maxX, region.minY, region.maxY, region.minZ, region.maxZ)
        }
    }
}
