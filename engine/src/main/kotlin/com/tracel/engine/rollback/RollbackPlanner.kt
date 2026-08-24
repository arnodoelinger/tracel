package com.tracel.engine.rollback

import com.tracel.engine.ledger.LotRepository
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.TxnId
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
public class RollbackPlanner(
    private val repo: LotRepository,
    private val worldQuery: WorldQuery,
    private val maxTransformDepth: Int = 3,
) {
    private val unmakeSteps = linkedMapOf<TxnId, RollbackStep.Unmake>()
    private val resolved = mutableMapOf<LotId, ResolvedLocation>()

    public suspend fun plan(rootLots: List<LotId>): RollbackPlan = repo.atomically {
        unmakeSteps.clear()
        resolved.clear()

        val leafSteps = mutableListOf<RollbackStep>()
        val seenLeaf = mutableSetOf<LotId>()
        val stack = ArrayDeque(rootLots)

        while (stack.isNotEmpty()) {
            when (val location = resolve(stack.removeLast(), depth = 0)) {
                is ResolvedLocation.Split -> stack.addAll(location.children)
                is ResolvedLocation.Holder -> if (seenLeaf.add(location.lotId)) {
                    leafSteps += actionFor(location)
                }
            }
        }

        RollbackPlan(unmakeSteps.values.toList() + leafSteps)
    }

    /**
     * Dry-runs "where would this lot's material end up if the plan were
     * applied right now", without touching the ledger. Memoized: the same
     * crafted output is reached once per sibling ingredient whenever a craft
     * used more than one traced lot at a time.
     */
    private suspend fun resolve(lotId: LotId, depth: Int): ResolvedLocation = resolved.getOrPut(lotId) {
        val edges = repo.edgesFrom(lotId)

        // Once a lot has been compensated, its story is over
        val compensate = edges.filterIsInstance<LotEdge.Compensate>().firstOrNull()
        if (compensate != null) {
            error(
                "lot $lotId was already compensated by rollback job ${compensate.rollbackJob.raw} " +
                    "(replacement lot ${compensate.child.raw}) — refusing to compensate it again"
            )
        }

        val transform = edges.filterIsInstance<LotEdge.Transform>().firstOrNull()
        if (transform != null) {
            check(depth < maxTransformDepth) {
                "transform chain for lot $lotId exceeds max depth $maxTransformDepth — treat as unrecoverable and mint instead"
            }
            registerUnmake(transform)
            // What happens to the crafted output later is irrelevant for this ingredient: wherever it goes,
            // the rollback will unmake the craft and put the ingredient back where the output was at the time
            // of crafting.
            return@getOrPut when (val outputLocation = resolve(transform.child, depth + 1)) {
                is ResolvedLocation.Holder -> ResolvedLocation.Holder(lotId, outputLocation.holder, transform.quantity)
                is ResolvedLocation.Split -> error(
                    "crafted output ${transform.child} was later split into ${outputLocation.children.size} pieces"
                )
            }
        }

        val splits = edges.filterIsInstance<LotEdge.Split>()
        if (splits.isNotEmpty()) {
            return@getOrPut ResolvedLocation.Split(splits.map { it.child })
        }

        val lot = repo.lot(lotId)
        val holder = repo.currentHolderOf(lotId)
            ?: error("lot $lotId has no placement and no outgoing edges")
        ResolvedLocation.Holder(lotId, holder, lot.quantity)
    }

    private suspend fun registerUnmake(transform: LotEdge.Transform) {
        unmakeSteps.getOrPut(transform.craftedBy) {
            val inputs = repo.edgesInto(transform.child)
                .filterIsInstance<LotEdge.Transform>()
                .map { LotContribution(it.parent, it.quantity) }
            RollbackStep.Unmake(transform.child, inputs, transform.craftedBy, transform.producedAt)
        }
    }

    private fun actionFor(location: ResolvedLocation.Holder): RollbackStep {
        val (lotId, holder, quantity) = location
        return when (holder) {
            is HolderId.Sink -> RollbackStep.Mint(lotId, quantity, holder.kind)
            is HolderId.Player -> if (worldQuery.isOnline(holder.uuid)) {
                RollbackStep.Take(lotId, quantity, holder)
            } else {
                RollbackStep.Debt(lotId, quantity, holder.uuid)
            }
            else -> RollbackStep.Take(lotId, quantity, holder)
        }
    }
}
