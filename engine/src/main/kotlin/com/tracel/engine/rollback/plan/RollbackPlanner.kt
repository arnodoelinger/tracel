package com.tracel.engine.rollback.plan

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.engine.ledger.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.TxnId
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge

/**
 * Works out what a rollback actually has to do to reclaim a set of traced
 * lots.
 *
 * Start from the lots you want back, and follow every path their material could
 * have taken since — split into smaller stacks, merged into a craft, sitting
 * untouched somewhere. A path is done exactly when it reaches something you
 * can point at right now: a real holder to take from, a sink to mint a
 * replacement for, an offline player who owes a debt.
 *
 * A craft is the one case where "follow the path" and "here is the action"
 * genuinely disagree: the traced ingredient did not just move, it got
 * absorbed into something that now has to be destroyed to get it back out.
 *
 * [resolve] handles that by asking where the crafted output currently
 * ends up — recursively, so a chain of several crafts unwinds the same way a
 * chain of splits does — and then reporting that holder back against the
 * original ingredient's own lot and quantity, since that is what
 * reappears there once the craft is undone.
 */
@RunsOn(ThreadContext.ASYNC)
@Suppress("UNUSED")
public class RollbackPlanner(
    private val repo: LotRepository,
    private val worldQuery: WorldQuery,
    private val maxTransformDepth: Int = 3,
    private val vanished: Set<HolderId> = emptySet(),
) {
    private val unmakeSteps = linkedMapOf<TxnId, RollbackStep.Unmake>()
    private val resolved = mutableMapOf<LotId, ResolvedLocation>()
    private val edgeCache = mutableMapOf<LotId, List<LotEdge>>()
    private val intoCache = mutableMapOf<LotId, List<LotEdge>>()
    private val lotCache = mutableMapOf<LotId, Lot>()
    private val holderCache = mutableMapOf<LotId, HolderId>()

    public suspend fun plan(rootLots: List<LotId>): RollbackPlan = repo.reading {
        unmakeSteps.clear()
        resolved.clear()
        edgeCache.clear()
        intoCache.clear()
        lotCache.clear()
        holderCache.clear()

        var frontier = rootLots.distinct()
        while (frontier.isNotEmpty()) {
            edgeCache.putAll(repo.edgesFromAll(frontier.filter { it !in edgeCache }))
            val next = ArrayList<LotId>()
            for (id in frontier) {
                for (edge in edgeCache[id].orEmpty()) {
                    val child = when (edge) {
                        is LotEdge.Split, is LotEdge.Transform -> edge.child
                        is LotEdge.Compensate -> continue
                    }
                    if (child !in edgeCache) next += child
                }
            }
            frontier = next.distinct()
        }

        val transformChildren = LinkedHashSet<LotId>()
        for (edges in edgeCache.values) {
            for (edge in edges) if (edge is LotEdge.Transform) transformChildren += edge.child
        }
        if (transformChildren.isNotEmpty()) intoCache.putAll(repo.edgesIntoAll(transformChildren))

        val allLots = LinkedHashSet<LotId>(edgeCache.size + transformChildren.size)
        allLots.addAll(edgeCache.keys)
        allLots.addAll(transformChildren)
        if (allLots.isNotEmpty()) {
            lotCache.putAll(repo.lotsOfAll(allLots))
            holderCache.putAll(repo.currentHoldersOf(allLots))
        }

        val leafSteps = mutableListOf<RollbackStep>()
        val seenLeaf = mutableSetOf<LotId>()
        val rootOf = mutableMapOf<LotId, LotId>()
        val settled = mutableSetOf<LotId>()
        val stack = ArrayDeque(rootLots.map { it to it })

        while (stack.isNotEmpty()) {
            val (lotId, root) = stack.removeLast()
            when (val location = resolve(lotId, depth = 0)) {
                is ResolvedLocation.Split -> {
                    location.children.forEach { stack.addLast(it to root) }
                }
                is ResolvedLocation.Settled -> settled += location.lotId
                is ResolvedLocation.Holder -> if (seenLeaf.add(location.lotId)) {
                    rootOf[location.lotId] = root
                    leafSteps += actionFor(location)
                }
            }
        }

        for ((outputLot, inputs) in unmakeSteps.values) rootOf.putIfAbsent(outputLot, rootOf[inputs.firstOrNull()?.lotId] ?: outputLot)

        RollbackPlan(unmakeSteps.values.toList() + leafSteps, rootOf, settled)
    }

    private suspend fun resolve(lotId: LotId, depth: Int): ResolvedLocation = resolved.getOrPut(lotId) {
        val edges = edgeCache[lotId].orEmpty()

        // Once a lot has been compensated, its story is over
        val compensate = edges.filterIsInstance<LotEdge.Compensate>().firstOrNull()
        if (compensate != null) return@getOrPut ResolvedLocation.Settled(lotId, compensate.rollbackJob)

        val transform = edges.filterIsInstance<LotEdge.Transform>().firstOrNull()
        if (transform != null) {
            if (depth >= maxTransformDepth) {
                val lot = lotOf(lotId)
                return@getOrPut ResolvedLocation.Holder(lotId, HolderId.Sink(SinkKind.UNTRACKED_GAP), lot.quantity)
            }
            return@getOrPut when (val outputLocation = resolve(transform.child, depth + 1)) {
                is ResolvedLocation.Holder -> {
                    registerUnmake(transform, outputLocation.holder)
                    ResolvedLocation.Holder(lotId, outputLocation.holder, transform.quantity)
                }
                is ResolvedLocation.Settled -> ResolvedLocation.Settled(lotId, outputLocation.byJob)
                is ResolvedLocation.Split -> outputLocation
            }
        }

        val splits = edges.filterIsInstance<LotEdge.Split>()
        if (splits.isNotEmpty()) {
            return@getOrPut ResolvedLocation.Split(splits.map { it.child })
        }

        val lot = lotOf(lotId)
        val holder = holderCache[lotId]
            ?: error("lot $lotId has no placement and no outgoing edges")
        ResolvedLocation.Holder(lotId, holder, lot.quantity)
    }

    private fun lotOf(lotId: LotId): Lot = lotCache.getValue(lotId)

    private fun registerUnmake(transform: LotEdge.Transform, holder: HolderId) {
        unmakeSteps.getOrPut(transform.craftedBy) {
            val inputs = intoCache[transform.child].orEmpty()
                .filterIsInstance<LotEdge.Transform>()
                .map { LotContribution(it.parent, it.quantity) }
            RollbackStep.Unmake(transform.child, inputs, transform.craftedBy, holder)
        }
    }

    private fun actionFor(location: ResolvedLocation.Holder): RollbackStep {
        val (lotId, holder, quantity) = location
        if (holder in vanished) return RollbackStep.Mint(lotId, quantity, SinkKind.UNTRACKED_GAP)
        return when (holder) {
            is HolderId.Sink -> RollbackStep.Mint(lotId, quantity, holder.kind)
            else -> RollbackStep.Take(lotId, quantity, holder) // Offline is still a "Take"
        }
    }
}
