package com.tracel.plugin.rollback.survey

import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackPlanner
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.id.LotId
import com.tracel.model.id.Seq
import com.tracel.model.lot.LotEdge
import com.tracel.model.transaction.Transaction
import com.tracel.plugin.rollback.composer.RollbackComposer
import com.tracel.plugin.rollback.trace.RollbackTrace
import com.tracel.plugin.util.namedByEntity
import java.util.UUID

/**
 * Turns the windowed transactions into a [MaterialSurvey]: where every lot roots,
 * what vanished, what to replay.
 */
internal suspend fun RollbackComposer.planMaterial(
    txns: List<Transaction>,
    trace: RollbackTrace,
    keepCargoOn: Set<UUID> = emptySet(),
    structural: Boolean = true,
): MaterialSurvey {

    // Census candidates from the log + current holders, before the graph walk. Asking after
    // planning serialized a Folia hop behind the walk and then walked twice.
    val candidates = HashSet<HolderId>()
    for (txn in txns) {
        for ((_, _, source, destination) in txn.flows) {
            if (source.namedByEntity()) candidates += source
            if (destination.namedByEntity()) candidates += destination
        }
    }

    val byRoot = LinkedHashMap<LotId, HolderId>()
    val needLots = ArrayList<Seq>()
    for (txn in txns) {
        if (txn.lots.isEmpty()) needLots += txn.seq
        // Same-txn mint-through mob is not a home
        val passedThrough = txn.mintedStraightThrough()
        for ((flowIndex, lotId) in txn.lots) {
            val flow = txn.flows.getOrNull(flowIndex) ?: continue
            // Transform walk already unmakes it and returns the ingredient
            if (flow.kind == FlowKind.TRANSFORM_OUT) continue
            if (flow.isGapCorrection()) continue
            byRoot.rootedAt(lotId, passedThrough[flow.source] ?: flow.source, keepCargoOn)
        }
    }

    // Finish linkage before the vanished census — storage ms vs. a region tick, twice
    val extraLots = if (needLots.isEmpty()) null else {
        trace.span("txn lot linkage") { services.log.lotsAtAll(needLots) }
    }
    if (extraLots != null) {
        for (txn in txns) {
            if (txn.lots.isNotEmpty()) continue
            val passedThrough = txn.mintedStraightThrough()
            for ((flowIndex, lotId) in extraLots[txn.seq].orEmpty()) {
                val flow = txn.flows.getOrNull(flowIndex) ?: continue
                if (flow.kind == FlowKind.TRANSFORM_OUT) continue
                if (flow.isGapCorrection()) continue
                byRoot.rootedAt(lotId, passedThrough[flow.source] ?: flow.source, keepCargoOn)
            }
        }
    }

    // Current holders of roots; log does not say where lots sit now
    if (byRoot.isNotEmpty()) {
        val holders = trace.span("root holders") { services.repo.currentHoldersOf(byRoot.keys) }
        for (holder in holders.values) if (holder.namedByEntity()) candidates += holder
        val parentOf = HashMap<LotId, LotId>()
        var frontier: Collection<LotId> = byRoot.keys
        val seenLots = HashSet<LotId>()
        while (frontier.isNotEmpty()) {
            val into = services.repo.edgesIntoAll(frontier.filter { seenLots.add(it) })
            val next = ArrayList<LotId>()
            for ((child, edges) in into) {
                val split = edges.filterIsInstance<LotEdge.Split>().firstOrNull() ?: continue
                parentOf[child] = split.parent
                if (split.parent !in seenLots) next += split.parent
            }
            frontier = next
        }
        byRoot.inheritMintedBurns(holders, parentOf)
    }

    val checked = if (candidates.isEmpty()) emptySet() else {
        trace.span("find vanished items") { worldCensus.vanishedEntities(candidates, trace) }
    }

    val target = RollbackTarget.PerRoot(byRoot)
    val roots = byRoot.keys.sortedByDescending { it.raw }
    if (roots.isEmpty()) return MaterialSurvey(RollbackPlan(emptyList()), target, roots, emptySet(), null)

    var vanished = checked
    var planner = RollbackPlanner(services.repo, services.worldQuery, vanished = vanished, structural = structural)
    var plan = trace.span("plan material") { planner.plan(roots) }

    // Holders the plan named that the log never mentioned (pre-window drops).
    // Replan only if vanished.
    val unchecked = plan.holders.filterTo(HashSet()) { it.namedByEntity() && it !in candidates }
    if (unchecked.isNotEmpty()) {
        val late = trace.span("find vanished items") { worldCensus.vanishedEntities(unchecked, trace) }
        if (late.isNotEmpty()) {
            vanished = vanished + late
            planner = RollbackPlanner(services.repo, services.worldQuery, vanished = vanished, structural = structural)
            plan = trace.span("replan material") { planner.plan(roots) }
        }
    }

    trace.note("txns", txns.size)
    trace.note("roots", roots.size)

    // Witness after planning so it covers what the plan saw.
    return MaterialSurvey(plan, target, roots, vanished, services.counters.peekTxnId(), planner.placedAndUnreachable)
}

private fun Flow.isGapCorrection(): Boolean =
    (source as? HolderId.Source)?.kind == SourceKind.UNTRACKED_GAP ||
            (destination as? HolderId.Sink)?.kind == SinkKind.UNTRACKED_GAP
