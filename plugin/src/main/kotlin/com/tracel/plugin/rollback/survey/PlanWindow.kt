package com.tracel.plugin.rollback.survey

import com.tracel.annotations.Unstable
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.engine.rollback.structure.structuralPartnerOf
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.plugin.rollback.composer.RollbackComposer
import java.util.*

/** Mobs that leave blocks behind them as they walk. */
@Unstable
private val LAYS_A_TRAIL = setOf("snow_golem")

/**
 * For every entity with trail blocks spawned in-window, fetch its full history and
 * fold in whatever it laid down.
 */
internal suspend fun RollbackComposer.withTrails(
    changes: List<WorldChange>,
    windowFilter: LookupFilter,
): List<WorldChange> {
    val layers = LinkedHashSet<UUID>()
    for (change in changes) {
        val subject = change.subject as? ChangeSubject.Entity ?: continue
        if (subject.before != null) continue
        if (subject.type.value.substringAfter(':') !in LAYS_A_TRAIL) continue
        layers += subject.entity
    }
    if (layers.isEmpty()) return changes

    val seen = changes.mapTo(HashSet()) { it.seq }
    val extra = ArrayList<WorldChange>()
    for (layer in layers) {
        val trail = services.worldLog.query(
            windowFilter.copy(
                holders = setOf(HolderId.Entity(layer)),
                region = null,
                actions = emptySet(),
                material = null,
            ),
        )
        for (change in trail) if (seen.add(change.seq)) extra += change
    }
    return if (extra.isEmpty()) changes else changes + extra
}

/** Fetch missing partner cells (e.g. bads). */
internal suspend fun RollbackComposer.withStructuralPartners(
    changes: List<WorldChange>,
    windowFilter: LookupFilter,
): List<WorldChange> {
    val known = HashSet<BlockPos>(changes.size)
    for (change in changes) if (change.subject is ChangeSubject.Block) known += change.at

    val missing = LinkedHashSet<BlockPos>()
    for (change in changes) {
        val subject = change.subject as? ChangeSubject.Block ?: continue
        structuralPartnerOf(change.at, subject.before)?.let { if (it !in known) missing += it }
        structuralPartnerOf(change.at, subject.after)?.let { if (it !in known) missing += it }
    }
    if (missing.isEmpty()) return changes

    // One query per chunk, over the box its missing cells span, kept to exactly those cells:
    // a scope edge through a row of beds was one full query per bed.
    val extra = ArrayList<WorldChange>()
    for ((chunk, cells) in missing.groupBy { Triple(it.world, it.x shr 4, it.z shr 4) }) {
        val (world, cx, cz) = chunk
        val box = LookupRegion(
            world = world,
            minChunkX = cx, maxChunkX = cx,
            minChunkZ = cz, maxChunkZ = cz,
            minX = cells.minOf { it.x }, maxX = cells.maxOf { it.x },
            minY = cells.minOf { it.y }, maxY = cells.maxOf { it.y },
            minZ = cells.minOf { it.z }, maxZ = cells.maxOf { it.z },
        )
        val wanted = cells.toHashSet()
        services.worldLog.query(windowFilter.copy(region = box)).filterTo(extra) { it.at in wanted }
    }
    return if (extra.isEmpty()) changes else changes + extra
}
