package com.tracel.plugin.rollback.material

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.holder.restoreEntityCargo
import com.tracel.plugin.rollback.material.holder.restoreGroundItems
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.formsFor
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.recordSpills
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.entityUuid
import com.tracel.plugin.util.namedByEntity
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level

@Unstable
internal const val SAMPLED_FAILURES = 3

/**
 * Applies [deltas] to their holders after the ledger.
 *
 * Fans out per region.
 */
@Unstable
internal suspend fun MaterialRestorer.restoreDeltas(
    deltas: Map<HolderId, Map<ItemKey, Long>>,
    job: RollbackJobId,
    knownGone: Set<HolderId>?,
    respawnAt: Map<HolderId.ItemEntity, HolderId>,
    trace: RollbackTrace,
    census: EntityCensus,
    settled: CompletableDeferred<Set<HolderId>>?,
    asOf: Long?,
): RestorationReport {
    // One spill sink across region threads; recorded once at the end
    val sink = ConcurrentLinkedQueue<Spill>()
    val work = deltas
        .mapValues { (_, itemDeltas) -> itemDeltas.filterValues { it != 0L } }
        .filterValues { it.isNotEmpty() }
    val forms = trace.span("move items / item forms") { formsFor(work) }

    val unchecked = work.keys.filterTo(HashSet()) { it.namedByEntity() && it !in knownGone.orEmpty() }
    val counted = unchecked.filter { holder -> holder.entityUuid().let { it in census.missing || it in census.at } }
    val censusGone = counted.filterTo(HashSet()) { it.entityUuid() in census.missing }
    val unknown = unchecked - counted.toSet()
    val gone = when {
        unknown.isEmpty() -> knownGone.orEmpty() + censusGone
        else -> knownGone.orEmpty() + censusGone +
                trace.span("move items / vanished") {
                    vanishedEntities(unknown, trace)
                }
    }

    val worn = WornStacks()
    val early = wornTakes(work)
    val late = if (early.isEmpty()) work else work
        .mapValues { (holder, deltas) -> early[holder]?.let { deltas - it.keys } ?: deltas }
        .filterValues { it.isNotEmpty() }
    val outcomes = if (early.isEmpty()) {
        fanOut(late, job, gone, respawnAt, trace, census, forms, sink, settled, asOf, worn)
    } else {
        val wornKeys = early.values.flatMapTo(HashSet()) { it.keys }
        val (waiting, free) = late.entries.partition { (holder, deltas) ->
            holder in early || deltas.any { (key, delta) -> delta > 0L && key in wornKeys }
        }
        coroutineScope {
            val freeRun = async {
                fanOut(
                    free.associate { it.toPair() },
                    job,
                    gone,
                    respawnAt,
                    trace,
                    census,
                    forms,
                    sink,
                    null,
                    asOf,
                    worn
                )
            }
            val first = fanOut(early, job, gone, respawnAt, trace, census, forms, sink, null, asOf, worn)
            val second = fanOut(
                waiting.associate { it.toPair() },
                job,
                gone,
                respawnAt,
                trace,
                census,
                forms,
                sink,
                null,
                asOf,
                worn
            )
            val all = first + freeRun.await() + second
            settled?.complete(all.mapNotNullTo(HashSet()) { (holder, result) -> holder.takeIf { result is ApplyResult.Failed } })
            all
        }
    }

    val failures = mutableMapOf<HolderId, String>()
    val queued = mutableMapOf<HolderId, String>()
    for ((holder, result) in outcomes) {
        when (result) {
            is ApplyResult.Failed -> failures[holder] = result.reason
            is ApplyResult.Queued -> queued[holder] = result.note
            ApplyResult.Ok -> {}
        }
    }

    if (failures.isNotEmpty()) {
        logger.log(
            Level.WARNING,
            "physical restoration incomplete for ${failures.size} holder(s) in rollback job ${job.raw}; " +
                    "first: ${failures.entries.take(SAMPLED_FAILURES).joinToString("; ") { "${it.key}: ${it.value}" }}",
        )
    }

    recordSpills(sink)

    return RestorationReport(failures, queued, sink.size)
}

private suspend fun MaterialRestorer.fanOut(
    work: Map<HolderId, Map<ItemKey, Long>>,
    job: RollbackJobId,
    gone: Set<HolderId>,
    respawnAt: Map<HolderId.ItemEntity, HolderId>,
    trace: RollbackTrace,
    census: EntityCensus,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
    settled: CompletableDeferred<Set<HolderId>>?,
    asOf: Long?,
    worn: WornStacks,
    failedEarly: Set<HolderId> = emptySet(),
): List<Pair<HolderId, ApplyResult>> {
    val ground = LinkedHashMap<HolderId.ItemEntity, Map<ItemKey, Long>>()
    val cargo = LinkedHashMap<HolderId.Entity, Map<ItemKey, Long>>()
    val rest = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    for ((holder, nonZero) in work) {
        when (holder) {
            is HolderId.ItemEntity -> ground[holder] = nonZero
            is HolderId.Entity -> cargo[holder] = nonZero
            else -> rest[holder] = nonZero
        }
    }

    return coroutineScope {
        val items = async {
            if (ground.isEmpty()) emptyList()
            else trace.span("move items / ground items") {
                restoreGroundItems(
                    ground,
                    gone,
                    census,
                    forms,
                    sink,
                    respawnAt,
                    worn
                )
            }
        }
        val cargoJob = async {
            if (cargo.isEmpty()) emptyList()
            else trace.span("move items / entity cargo") {
                restoreEntityCargo(
                    cargo,
                    forms,
                    gone,
                    census,
                    sink,
                    asOf,
                    worn
                )
            }
        }
        val others = rest.entries.groupBy { it.key.regionKey() }.values.map { group ->
            async {
                val each = suspend {
                    group.map { (holder, nonZero) ->
                        val taking = nonZero.values.all { it < 0L }
                        if (holder in gone && taking) holder to ENTITY_GONE_AT_PLAN
                        else {
                            val kind = HolderGroup.of(holder)
                            holder to trace.span("move items / ${kind.traceName}") {
                                applyTo(
                                    holder,
                                    nonZero,
                                    forms,
                                    job,
                                    sink,
                                    asOf,
                                    worn
                                )
                            }
                        }
                    }
                }
                when (val first = group.first().key) {
                    is HolderId.Block -> withContext(services.schedulers.region(first)) { each() }
                    is HolderId.PlacedBlock -> withContext(
                        services.schedulers.region(
                            HolderId.Block(
                                first.world,
                                first.x,
                                first.y,
                                first.z
                            )
                        )
                    ) { each() }

                    else -> each()
                }
            }
        }
        val holders = others.awaitAll().flatten() + cargoJob.await()
        settled?.complete(holders.mapNotNullTo(HashSet(failedEarly)) { (holder, result) -> holder.takeIf { result is ApplyResult.Failed } })
        holders + items.await()
    }
}

private fun wornTakes(work: Map<HolderId, Map<ItemKey, Long>>): Map<HolderId, Map<ItemKey, Long>> {
    val given = HashSet<ItemKey>()
    for (deltas in work.values) for ((key, delta) in deltas) if (delta > 0L) given += key
    val out = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    for ((holder, deltas) in work) {
        val taken = deltas.filter { (key, delta) -> delta < 0L && key in given && WornStacks.wears(key) }
        if (taken.isNotEmpty()) out[holder] = taken
    }
    return out
}
