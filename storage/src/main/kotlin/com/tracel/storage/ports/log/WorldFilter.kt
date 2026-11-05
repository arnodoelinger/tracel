package com.tracel.storage.ports.log

import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.cause.CauseKind
import com.tracel.model.world.*
import com.tracel.storage.StorageUnit
import com.tracel.storage.codec.BlockChange
import com.tracel.storage.codec.Section
import com.tracel.storage.codec.Wchg
import com.tracel.storage.intern.Interning
import java.lang.foreign.MemorySegment

/**
 * What a [LookupFilter] lets through, asked of a world change's record or of
 * the spatial row that stands for it.
 *
 * The record form resolves block data through [Interning],
 * the row form answers from the row alone.
 */
internal object WorldFilter {
    /** Whether the world change [record] is one [filter] asks for. */
    fun accepts(unit: StorageUnit, interning: Interning, record: MemorySegment, filter: LookupFilter, ids: FilterIds): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val regionWorldId = ids.regionWorld
        val filterWorldId = ids.world
        val epochMillis = Wchg.epochMillis[record]
        val since = filter.since
        val until = filter.until
        if (since != null && epochMillis < since) return false
        if (until != null && epochMillis > until) return false
        val cause = Wchg.cause[record]
        if (cause.isBookkeeping) return false
        val causes = filter.worldCauses ?: filter.causes
        if (causes.isNotEmpty() && cause !in causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (filter.actions.isNotEmpty() && Wchg.action[record] !in filter.actions) return false
        val causedBy = Wchg.causedBy[record]
        if (holderIds.isNotEmpty() && causedBy !in holderIds) return false
        if (excludedIds.isNotEmpty() && causedBy in excludedIds) return false

        val worldId = Wchg.world[record]
        if (filterWorldId != null && worldId != filterWorldId) return false
        val region = filter.region
        if (region != null && (regionWorldId == null || worldId != regionWorldId)) return false
        if (Wchg.kind[record] == Wchg.SECTION) {
            if (region != null && !sectionMeets(record, region)) return false
            val material = filter.material ?: return true
            for (slot in 0 until Section.paletteSize(record)) {
                val data = interning.resolveBlockData(unit, Section.paletteAt(record, slot)).value
                if (data.matchesAny(material, filter.blockMaterials)) return true
            }
            return false
        }

        if (region != null &&
            !region.containsBlock(Wchg.x[record], Wchg.y[record], Wchg.z[record])
        ) {
            return false
        }

        val material = filter.material
        if (material != null) {
            if (Wchg.kind[record] != Wchg.BLOCK) return false
            val before = interning.resolveBlockData(unit, BlockChange.before[record]).value
            val after = interning.resolveBlockData(unit, BlockChange.after[record]).value
            if (!before.matchesAny(material, filter.blockMaterials) && !after.matchesAny(
                    material,
                    filter.blockMaterials
                )
            ) return false
        }
        return true
    }

    private fun sectionMeets(record: MemorySegment, region: LookupRegion): Boolean {
        val x = Wchg.x[record]
        val y = Wchg.y[record]
        val z = Wchg.z[record]
        if ((x shr 4) !in region.minTileX..region.maxTileX) return false
        if ((z shr 4) !in region.minTileZ..region.maxTileZ) return false
        return x + 15 >= region.minX && x <= region.maxX &&
                y + 15 >= region.minY && y <= region.maxY &&
                z + 15 >= region.minZ && z <= region.maxZ
    }

    /** [accepts] for a row of the spatial index, before its record is read, if it ever is. */
    fun acceptsRow(rows: SpatialRows, row: Int, filter: LookupFilter, ids: FilterIds): Boolean {
        val holderIds = ids.holders
        val excludedIds = ids.excluded
        val cause = CauseKind.entries[rows.causes[row].toInt() and 0xFF]
        if (cause.isBookkeeping) return false
        val causes = filter.worldCauses ?: filter.causes
        if (causes.isNotEmpty() && cause !in causes) return false
        if (filter.excludedCauses.isNotEmpty() && cause in filter.excludedCauses) return false
        if (rows.deferred[row]) return true
        if (filter.actions.isNotEmpty() &&
            ActionKind.entries[rows.actions[row].toInt() and 0xFF] !in filter.actions
        ) {
            return false
        }
        val causedBy = rows.causedByIds[row]
        if (holderIds.isNotEmpty() && causedBy !in holderIds) return false
        if (excludedIds.isNotEmpty() && causedBy in excludedIds) return false
        return true
    }

    /** Whether [blockData] is [material] or one of its [aliases]: a bare name matches every state of it. */
    fun matches(blockData: String, material: String, aliases: Set<String>): Boolean = blockData.matchesAny(material, aliases)

    private fun String.matchesAny(material: String, aliases: Set<String>): Boolean =
        materialEquals(material) || aliases.any { materialEquals(it) }

    private fun String.materialEquals(material: String): Boolean {
        if ('[' in material) return equals(material, ignoreCase = true)
        val stored = substringBefore('[')
        if (stored.equals(material, ignoreCase = true)) return true
        val bare = stored.substringAfter(':')
        val wanted = material.substringAfter(':')
        return bare.equals(wanted, ignoreCase = true)
    }
}
