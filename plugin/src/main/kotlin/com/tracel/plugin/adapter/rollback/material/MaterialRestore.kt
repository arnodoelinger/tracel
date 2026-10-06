package com.tracel.plugin.adapter.rollback.material

import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.adapter.rollback.material.holder.restoreEntityCargo
import com.tracel.plugin.adapter.rollback.material.holder.restoreGroundItems
import com.tracel.plugin.adapter.rollback.material.item.WornStacks
import com.tracel.plugin.adapter.rollback.material.item.formsFor
import com.tracel.plugin.adapter.rollback.material.spill.recordSpills
import com.tracel.plugin.adapter.world.ownsChunkAt
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.governor.throttled
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.ENTITY_GONE_AT_PLAN
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.shortfallOf
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.withholdUnfunded
import com.tracel.plugin.rollback.result.report.RestorationReport
import com.tracel.plugin.util.geometry.regionKey
import com.tracel.plugin.util.holder.entityUuid
import com.tracel.plugin.util.holder.namedByEntity
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import kotlinx.coroutines.*
import org.bukkit.World

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
    census: EntityCensus,
    settled: CompletableDeferred<Set<HolderId>>?,
    asOf: Long?,
): RestorationReport {
    // One spill sink across region threads; recorded once at the end
    val sink = ConcurrentLinkedQueue<Spill>()
    val work = deltas
        .mapValues { (_, itemDeltas) -> itemDeltas.filterValues { it != 0L } }
        .filterValues { it.isNotEmpty() }
    val forms = formsFor(work)

    val unchecked = work.keys.filterTo(HashSet()) { it.namedByEntity() && it !in knownGone.orEmpty() }
    val counted = unchecked.filter { holder -> holder.entityUuid().let { it in census.missing || it in census.at } }
    val censusGone = counted.filterTo(HashSet()) { it.entityUuid() in census.missing }
    val unknown = unchecked - counted.toSet()
    val gone = when {
        unknown.isEmpty() -> knownGone.orEmpty() + censusGone
        else -> knownGone.orEmpty() + censusGone +
                vanishedEntities(unknown)
    }

    val worn = WornStacks()

    // Takes first, then gives. What a holder could not give up must not be handed out again elsewhere: that copy is
    // a duplicate, and every rollback and undo after it compounds it.
    val takes = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    val gives = LinkedHashMap<HolderId, Map<ItemKey, Long>>()
    for ((holder, itemDeltas) in work) {
        val out = itemDeltas.filterValues { it < 0L }
        val into = itemDeltas.filterValues { it > 0L }
        if (out.isNotEmpty()) takes[holder] = out
        if (into.isNotEmpty()) gives[holder] = into
    }
    val tookOut = fanOut(takes, job, gone, respawnAt, census, forms, sink, null, asOf, worn)
    val short = shortfallOf(takes, tookOut)
    val unfunded = withholdUnfunded(gives, short)
    val gaveIn = fanOut(
        unfunded.funded, job, gone, respawnAt, census, forms, sink, settled, asOf, worn,
        failedEarly = tookOut.mapNotNullTo(HashSet()) { (holder, result) ->
            holder.takeIf { holder !is HolderId.ItemEntity && result is ApplyResult.Failed }
        },
    )
    val outcomes = tookOut + gaveIn

    val failures = mutableMapOf<HolderId, String>()
    val queued = mutableMapOf<HolderId, String>()
    for ((holder, result) in outcomes) {
        when (result) {
            is ApplyResult.Failed -> failures[holder] = result.reason
            is ApplyResult.Queued -> queued[holder] = result.note
            ApplyResult.Ok -> {}
        }
    }

    for ((holder, cut) in unfunded.withheld) {
        val what = cut.entries.joinToString(", ") { (key, amount) -> "${key.material} x$amount" }
        failures.merge(holder, "$what was not handed out: the matching take could not be made") { a, b -> "$a; $b" }
    }
    if (short.isNotEmpty()) {
        logger.log(
            Level.WARNING,
            "rollback job ${job.raw}: " + short.entries.joinToString(", ") { (key, amount) -> "${key.material} x$amount" } +
                    " could not be taken, so that much was not handed out" +
                    if (unfunded.unspent.isEmpty()) "" else "; " +
                            unfunded.unspent.entries.joinToString(", ") { (key, amount) -> "${key.material} x$amount" } +
                            " found nothing to hold back, the world now has a spare",
        )
    }
    if (failures.isNotEmpty()) {
        logger.log(
            Level.WARNING,
            "physical restoration incomplete for ${failures.size} holder(s) in rollback job ${job.raw}; " +
                    "first: ${failures.entries.take(SAMPLED_FAILURES).joinToString("; ") { "${it.key}: ${it.value}" }}",
        )
    }

    recordSpills(sink)

    return RestorationReport(failures, queued, sink.size, unfunded.unspent)
}

private suspend fun MaterialRestorer.fanOut(
    work: Map<HolderId, Map<ItemKey, Long>>,
    job: RollbackJobId,
    gone: Set<HolderId>,
    respawnAt: Map<HolderId.ItemEntity, HolderId>,
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
            else restoreGroundItems(
                ground,
                gone,
                census,
                forms,
                sink,
                respawnAt,
                worn
            )
        }
        val cargoJob = async {
            if (cargo.isEmpty()) emptyList()
            else restoreEntityCargo(
                cargo,
                forms,
                gone,
                census,
                sink,
                asOf,
                worn
            )
        }
        val others = rest.entries.groupBy { it.key.regionKey() }.values.map { group ->
            async {
                val one = suspend { holder: HolderId, nonZero: Map<ItemKey, Long> ->
                    val taking = nonZero.values.all { it < 0L }
                    if (holder in gone && taking) holder to ENTITY_GONE_AT_PLAN
                    else {
                        holder to applyTo(
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
                val each = suspend { group.map { (holder, nonZero) -> one(holder, nonZero) } }

                suspend fun inTurns(world: World?, at: HolderId.Block): List<Pair<HolderId, ApplyResult>> {
                    if (world == null) return each()
                    return services.governor.throttled(world, at.x shr 4, at.z shr 4) { throttle ->
                        val out = ArrayList<Pair<HolderId, ApplyResult>>(group.size)
                        for ((holder, nonZero) in group) {
                            throttle.yieldIfSpent()
                            val cell = when (holder) {
                                is HolderId.Block -> holder
                                is HolderId.PlacedBlock -> HolderId.Block(holder.world, holder.x, holder.y, holder.z)
                                else -> null
                            }
                            out += if (cell == null || ownsChunkAt(world, cell.x, cell.z)) one(holder, nonZero)
                            else withContext(services.schedulers.region(cell)) { one(holder, nonZero) }
                        }
                        out
                    }
                }
                when (val first = group.first().key) {
                    is HolderId.Block -> withContext(services.schedulers.region(first)) {
                        inTurns(worldOf(first.world), first)
                    }

                    is HolderId.PlacedBlock -> {
                        val at = HolderId.Block(first.world, first.x, first.y, first.z)
                        withContext(services.schedulers.region(at)) { inTurns(worldOf(first.world), at) }
                    }

                    else -> each()
                }
            }
        }
        val holders = others.awaitAll().flatten() + cargoJob.await()
        settled?.complete(holders.mapNotNullTo(HashSet(failedEarly)) { (holder, result) -> holder.takeIf { result is ApplyResult.Failed } })
        holders + items.await()
    }
}
