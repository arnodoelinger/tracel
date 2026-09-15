package com.tracel.plugin.rollback.material

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.holder.restoreEntityCargo
import com.tracel.plugin.rollback.material.holder.restoreGroundItems
import com.tracel.plugin.rollback.material.item.formsFor
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.recordSpills
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.namedByEntity
import com.tracel.plugin.util.regionKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
    settled: CompletableDeferred<Unit>?,
    asOf: Long?,
): RestorationReport {
    // One spill sink across region threads; recorded once at the end
    val sink = ConcurrentLinkedQueue<Spill>()
    val work = deltas
        .mapValues { (_, itemDeltas) -> itemDeltas.filterValues { it != 0L } }
        .filterValues { it.isNotEmpty() }
    val forms = trace.span("move items / item forms") { formsFor(work) }

    val unchecked = work.keys.filterTo(HashSet()) { it.namedByEntity() && it !in knownGone.orEmpty() }
    val gone = when {
        unchecked.isEmpty() -> knownGone.orEmpty()
        else -> knownGone.orEmpty() +
            trace.span("move items / vanished") {
                vanishedEntities(unchecked, trace)
            }
    }

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

    val outcomes = coroutineScope {
        val items = async {
            if (ground.isEmpty()) emptyList()
            else trace.span("move items / ground items") { restoreGroundItems(ground, gone, census, forms, sink, respawnAt) }
        }
        val cargoJob = async {
            if (cargo.isEmpty()) emptyList()
            else trace.span("move items / entity cargo") { restoreEntityCargo(cargo, forms, gone, census, sink, asOf) }
        }
        val others = rest.entries.groupBy { it.key.regionKey() }.values.map { group ->
            async {
                group.map { (holder, nonZero) ->
                    val taking = nonZero.values.all { it < 0L }
                    if (holder in gone && taking) holder to ENTITY_GONE_AT_PLAN
                    else {
                        val kind = HolderGroup.of(holder)
                        holder to trace.span("move items / ${kind.traceName}") { applyTo(holder, nonZero, forms, job, sink, asOf) }
                    }
                }
            }
        }
        val holders = others.awaitAll().flatten() + cargoJob.await()
        settled?.complete(Unit)
        holders + items.await()
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
