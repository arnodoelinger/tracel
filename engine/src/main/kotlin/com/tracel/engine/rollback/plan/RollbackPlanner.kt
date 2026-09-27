package com.tracel.engine.rollback.plan

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.annotations.Unstable
import com.tracel.engine.ledger.LotRepository
import com.tracel.engine.ledger.PlacedRun
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.id.LotId
import com.tracel.model.id.TxnId
import com.tracel.model.lot.Lot
import com.tracel.model.lot.LotEdge

/**
 * Plans the material changes needed for a rollback.
 *
 * Resolves traced lots through their ledger history and produces the
 * [RollbackStep]s needed to recover them.
 */
@RunsOn(ThreadContext.ASYNC)
@Suppress("UNUSED")
public class RollbackPlanner(
    private val repo: LotRepository,
    private val worldQuery: WorldQuery,
    private val maxTransformDepth: Int = 32,
    private val vanished: Set<HolderId> = emptySet(),
    private val structural: Boolean = true,
    private val covered: Set<HolderId>? = null,
    private val target: RollbackTarget? = null,
) {
    private val unmakeSteps = linkedMapOf<TxnId, RollbackStep.Unmake>()
    private val resolved = mutableMapOf<LotId, Array<ResolvedLocation?>>()
    private val edgeCache = mutableMapOf<LotId, List<LotEdge>>()
    private val intoCache = mutableMapOf<LotId, List<LotEdge>>()
    private val lotCache = mutableMapOf<LotId, Lot>()
    private val holderCache = mutableMapOf<LotId, HolderId>()

    public val placedAndUnreachable: Int get() = placedLots.size

    private val placedLots = mutableSetOf<LotId>()

    private companion object {
        const val MAX_RUN = 4096
    }

    /**
     * Builds a rollback plan for [rootLots].
     *
     * Loads the relevant part of the lot graph first, then resolves each root into concrete
     * rollback steps without changing the ledger.
     */
    public suspend fun plan(rootLots: List<LotId>): RollbackPlan = repo.reading {
        unmakeSteps.clear()
        sharedOut.clear()
        placedLots.clear()
        resolved.clear()
        edgeCache.clear()
        intoCache.clear()
        lotCache.clear()
        holderCache.clear()

        val found = repo.placedRuns(rootLots)
        val runs = ArrayList<PlacedRun>(found.runs.size)
        val walked = ArrayList<LotId>(found.rest)
        for (run in found.runs) {
            if (run.holder.takenAsIs()) runs += run else for (lot in run.lots) walked += LotId(lot)
        }
        var runLots = LongArray(0)
        for (run in runs) runLots += run.lots
        runLots.sort()

        var frontier = walked.distinct()
        while (frontier.isNotEmpty()) {
            // TODO: changes here can silently make valid rollback targets unreachable,
            //  needs a proper fix
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
        val stack = ArrayDeque<Pair<LotId, LotId>>()
        for (root in walked.distinct().sortedByDescending { it.raw }) stack.addLast(root to root)

        while (stack.isNotEmpty()) {
            val (lotId, root) = stack.removeLast()
            when (val location = resolve(lotId, depth = 0)) {
                is ResolvedLocation.Split -> {
                    location.children.forEach { stack.addLast(it to root) }
                }

                is ResolvedLocation.Settled -> settled += location.lotId
                is ResolvedLocation.Gone -> Unit
                // is ResolvedLocation.Destroyed -> Unit
                is ResolvedLocation.Holder -> if (seenLeaf.add(location.lotId)) {
                    rootOf[location.lotId] = root
                    leafSteps += actionFor(location)
                }
            }
        }

        val unmade = HashSet<LotId>()
        for ((outputs, inputs) in unmakeSteps.values) {
            val root = rootOf[inputs.firstOrNull()?.lotId]
            for ((lotId) in outputs) {
                rootOf.putIfAbsent(lotId, root ?: lotId)
                unmade += lotId
            }
        }

        val leaves = leafSteps.filterNot { step ->
            step is RollbackStep.Take && (step.lotId in unmade || step.holder == homeOf(rootOf[step.lotId]) ||
                    runLots.binarySearch(step.lotId.raw) >= 0)
        }
        // A run lot is its own root even where the walk reached it through another one first
        if (runLots.isNotEmpty()) rootOf.keys.removeAll { it !in unmade && runLots.binarySearch(it.raw) >= 0 }
        RollbackPlan(stillConsumed(unmakeSteps.values.toList()) + leaves + takeRuns(runs, unmade), rootOf, settled)
    }

    // TODO: too dangerous but it works
    private fun takeRuns(runs: List<PlacedRun>, unmade: Set<LotId>): List<RollbackStep> {
        if (runs.isEmpty()) return emptyList()
        val byHolder = LinkedHashMap<HolderId, Pair<ArrayList<Long>, ArrayList<Long>>>()
        for (run in runs) {
            val (lots, quantities) = byHolder.getOrPut(run.holder) { ArrayList<Long>() to ArrayList() }
            for (k in run.lots.indices) {
                val lot = LotId(run.lots[k])
                if (lot in unmade || homeOf(lot) == run.holder) continue
                lots += run.lots[k]
                quantities += run.quantities[k]
            }
        }
        val out = ArrayList<RollbackStep>()
        for ((holder, entries) in byHolder) {
            val (lots, quantities) = entries
            var from = 0
            while (from < lots.size) {
                val until = minOf(from + MAX_RUN, lots.size) // TODO: max?
                out += RollbackStep.TakeRun(
                    LongArray(until - from) { lots[from + it] },
                    LongArray(until - from) { quantities[from + it] },
                    holder,
                )
                from = until
            }
        }
        return out
    }

    private fun HolderId.takenAsIs(): Boolean =
        this !in vanished && this !is HolderId.Sink && (!isPlacedThing() || reclaimedByStructure())

    private fun homeOf(root: LotId?): HolderId? = when (val to = target) {
        null -> null
        is RollbackTarget.Uniform -> to.holder
        is RollbackTarget.PerRoot -> root?.let { to.byRoot[it] }
    }

    private suspend fun stillConsumed(unmakes: List<RollbackStep.Unmake>): List<RollbackStep.Unmake> {
        if (unmakes.isEmpty()) return unmakes
        val inputs = unmakes.flatMapTo(LinkedHashSet()) { step -> step.inputs.map { it.lotId } }
        val unknown = inputs.filter { it !in holderCache }
        if (unknown.isNotEmpty()) holderCache.putAll(repo.currentHoldersOf(unknown))
        return unmakes.map { step ->
            val consumed = step.inputs.filter { holderCache[it.lotId] == null }
            if (consumed.size == step.inputs.size) step else step.copy(inputs = consumed)
        }
    }

    private suspend fun resolve(lotId: LotId, depth: Int): ResolvedLocation {
        val known = resolved.getOrPut(lotId) { arrayOfNulls(maxTransformDepth + 1) }
        return known[depth] ?: locate(lotId, depth).also { known[depth] = it }
    }

    /**
     * Resolves [lotId] to the place where its material can currently be recovered.
     *
     * Resolution follows the ledger forward from the original lot:
     *
     * - [LotEdge.Compensate] means another rollback already settled the lot
     * - [LotEdge.Transform] means the material became part of a crafted output
     * - [LotEdge.Split] means the material was divided into several lots
     * - No outgoing edge means the lot is currently held directly
     *
     * Transform edges are tried newest-first. A lot may have several transform edges after a
     * rollback was undone and the same material was crafted again. Older edges can point to
     * outputs that no longer exist and must not win over a newer surviving output.
     *
     * A transform is only turned into an unmake when its output can still be accounted for.
     * If the output is gone, the planner must not restore the original ingredients for free.
     *
     * [depth] limits recursive transform resolution. Hitting the limit produces an untracked
     * gap rather than continuing indefinitely or guessing where the material went.
     */
    @Unstable
    private suspend fun locate(lotId: LotId, depth: Int): ResolvedLocation {
        val edges = edgeCache[lotId].orEmpty()

        // Once a lot has been compensated, its story is over
        val compensate = edges.filterIsInstance<LotEdge.Compensate>().firstOrNull()
        if (compensate != null) return ResolvedLocation.Settled(lotId, compensate.rollbackJob)

        // Newest craft first, and skip the ones that lead nowhere. A lot ends up with more than
        // one "Transform" edge whenever its craft was unmade and the material was crafted again,
        // and the older edge points at an output that no longer exists.
        val transforms = edges.filterIsInstance<LotEdge.Transform>()
        if (transforms.isNotEmpty()) {
            // TODO: replace the depth limit with cycle-safe transform resolution or smth like that
            if (depth >= maxTransformDepth) {
                val lot = lotOf(lotId)
                return ResolvedLocation.Holder(lotId, HolderId.Sink(SinkKind.UNTRACKED_GAP), lot.quantity)
            }
            // TODO: do not reorder this in alpha, but should be changed in future
            for (transform in transforms.sortedByDescending { it.craftedBy.raw }) {
                when (val outputLocation = resolve(transform.child, depth + 1)) {
                    is ResolvedLocation.Holder -> {
                        // Only when there is an output left to take apart. Burned in lava,
                        // swallowed by an inventory reconcile, on a ground item that despawned
                        // — the craft's output is already gone, so actionFor compensates the
                        // ingredient below and that is the whole of the answer.
                        //
                        // Registered anyway, the step destroyed a lot already in a sink
                        // and then "restored" the ingredients into (!) that same sink, where
                        // nobody could reach them — while the compensation minted replacements
                        // for the very same ingredients.
                        if (outputLocation.holder.isReclaimable()) {
                            registerUnmake(transform, listOf(UnmadeOutput(outputLocation.lotId, outputLocation.holder)))
                            return ResolvedLocation.Holder(lotId, outputLocation.holder, transform.quantity)
                        }
                        val gone = outputLocation.holder as? HolderId.Sink ?: HolderId.Sink(SinkKind.UNTRACKED_GAP)
                        return ResolvedLocation.Holder(lotId, gone, transform.quantity)
                    }

                    is ResolvedLocation.Settled -> return ResolvedLocation.Settled(lotId, outputLocation.byJob)

                    // The crafted stack did not stay one lot. Spending part of it splits it, and
                    // the pieces are still this craft's output, so gather them and unmake the
                    // lot of them together.
                    //
                    // Handing the pieces back as ordinary leaves instead is what made a chain of
                    // crafts roll back only its last link. For example, the planks craft stopped
                    // being unmakeable the moment eight of its planks became a chest, and the
                    // rollback returned planks where it owed logs.
                    is ResolvedLocation.Split -> {
                        val pieces = wholeOutput(transform.child, outputLocation, depth + 1)
                            ?: return shareOf(transform, outputLocation)
                        registerUnmake(transform, pieces)
                        return ResolvedLocation.Holder(lotId, pieces.first().holder, transform.quantity)
                    }

                    // Dead end: this craft's output was destroyed and never revived under the
                    // same ID. Try the next edge; if they are all like that the ingredient stays
                    // where it is, rather than being handed back for free with nothing coming out
                    // of the player's hands to balance it.
                    // TODO: handle this case
                    is ResolvedLocation.Gone -> Unit
                }
            }
            return ResolvedLocation.Gone(lotId)
        }

        val splits = edges.filterIsInstance<LotEdge.Split>()
        if (splits.isNotEmpty()) {
            return ResolvedLocation.Split(splits.map { it.child })
        }

        val lot = lotOf(lotId)
        val holder = holderCache[lotId] ?: return ResolvedLocation.Gone(lotId)
        // Standing in the world as itself
        if (holder.isPlacedThing() && !holder.reclaimedByStructure()) {
            placedLots += lotId
            return ResolvedLocation.Gone(lotId)
        }
        return ResolvedLocation.Holder(lotId, holder, lot.quantity)
    }

    /**
     * Checks whether [this] represents the material itself rather than a holder containing it.
     *
     * Placed blocks and entities need structural rollback to become items again.
     */
    private fun HolderId.isPlacedThing(): Boolean =
        this is HolderId.PlacedBlock || this is HolderId.PlacedEntity

    /**
     * Checks whether material can actually be taken from [this].
     *
     * Sources, sinks, and escrow are bookkeeping locations rather than real places where rollback
     * can retrieve an item. [vanished] holders are treated the same way because their contents can
     * no longer be recovered.
     */
    private fun HolderId.isReclaimable(): Boolean =
        this !is HolderId.Sink && this !is HolderId.Source && this !is HolderId.Escrow && this !in vanished &&
                (!isPlacedThing() || reclaimedByStructure())

    private fun HolderId.reclaimedByStructure(): Boolean = structural && (covered == null || this in covered)

    /**
     * All lots used by the planner are loaded before resolution starts.
     *
     * @return the cached lot for [lotId].
     */
    private fun lotOf(lotId: LotId): Lot = lotCache.getValue(lotId)

    /**
     * Checks whether [output] still exists as one complete craft result.
     *
     * A crafted output may have been split into several lots or passed through more crafts before
     * reaching its current holder. Those pieces can still represent the original craft output and
     * can therefore be used to unmake it.
     *
     * The output is considered whole only when:
     *
     * 1. Every part can still be resolved
     * 2. No part has been compensated or lost
     * 3. Every part is held by a reclaimable holder
     * 4. All parts are at the same holder
     * 5. Their quantities add up to the original output quantity
     *
     * An incomplete output cannot pay for restoring all of the craft's inputs, so unmaking it would
     * create material that no longer has a corresponding output to destroy.
     *
     * @return the surviving output pieces when the craft can safely be unmade, or `null` when the
     * caller must fall back to resolving the pieces individually.
     */
    @Unstable
    // TODO: dangerous; make it better in future
    private suspend fun wholeOutput(
        output: LotId,
        split: ResolvedLocation.Split,
        depth: Int,
    ): List<UnmadeOutput>? {
        val pieces = ArrayList<UnmadeOutput>()
        val frontier = ArrayDeque(split.children)
        val seen = HashSet<LotId>()
        var covered = 0L
        while (frontier.isNotEmpty()) {
            val child = frontier.removeFirst()
            if (!seen.add(child)) continue
            when (val at = resolve(child, depth)) {
                is ResolvedLocation.Holder -> {
                    if (!at.holder.isReclaimable()) return null
                    pieces += UnmadeOutput(at.lotId, at.holder)
                    // What sits there now. A piece resolved through a further craft is not placed at all,
                    // and an unmake destroys that whole lot.
                    // the engine places whole lots, so the preloaded quantity is what sits there: no read per piece
                    covered += lotCache[at.lotId]?.quantity?.raw ?: repo.placementOf(
                        at.holder,
                        at.lotId
                    )?.remaining?.raw ?: 0L
                }

                is ResolvedLocation.Split -> frontier += at.children
                is ResolvedLocation.Settled, is ResolvedLocation.Gone -> return null
            }
        }
        if (pieces.isEmpty()) return null
        if (pieces.any { it.holder != pieces.first().holder }) return null

        // TODO: add more regression tests for partially lost crafted outputs
        if (covered != lotOf(output).quantity.raw) return null
        return pieces
    }

    private val sharedOut = HashMap<LotId, MutableSet<LotId>>()

    private fun shareOf(transform: LotEdge.Transform, split: ResolvedLocation.Split): ResolvedLocation {
        val inputs = intoCache[transform.child].orEmpty().filterIsInstance<LotEdge.Transform>()
        val total = inputs.sumOf { it.quantity.raw }
        if (inputs.size <= 1 || total <= 0L) return split
        val made = lotCache[transform.child]?.quantity?.raw ?: return split
        var owed = made * transform.quantity.raw / total
        val taken = sharedOut.getOrPut(transform.child) { HashSet() }
        val mine = ArrayList<LotId>()
        for (piece in split.children.sortedByDescending { lotCache[it]?.quantity?.raw ?: 0L }) {
            if (owed <= 0L) break
            if (piece in taken) continue
            val size = lotCache[piece]?.quantity?.raw ?: continue
            if (size > owed) continue
            taken += piece
            mine += piece
            owed -= size
        }
        return ResolvedLocation.Split(mine)
    }

    /**
     * Adds an [RollbackStep.Unmake] for a craft if one has not already been registered.
     *
     * All transform edges belonging to the same crafting transaction share one unmake step, so
     * the craft is undone as a single operation rather than as unrelated output pieces.
     */
    private fun registerUnmake(transform: LotEdge.Transform, outputs: List<UnmadeOutput>) {
        unmakeSteps.getOrPut(transform.craftedBy) {
            val inputs = intoCache[transform.child].orEmpty()
                .filterIsInstance<LotEdge.Transform>()
                .map { LotContribution(it.parent, it.quantity) }
            RollbackStep.Unmake(outputs, inputs, transform.craftedBy, outputs.first().holder)
        }
    }

    /**
     * Converts a resolved holder into the rollback action needed to recover its material.
     *
     * Real holders produce [RollbackStep.Take]. Sinks and vanished holders cannot provide the
     * original material, so they require compensation instead.
     */
    private fun actionFor(location: ResolvedLocation.Holder): RollbackStep {
        val (lotId, holder, quantity) = location
        if (holder in vanished) return RollbackStep.Mint(lotId, quantity, SinkKind.UNTRACKED_GAP)
        return when (holder) {
            is HolderId.Sink -> RollbackStep.Mint(lotId, quantity, holder.kind)
            else -> RollbackStep.Take(lotId, quantity, holder) // Offline is still a "Take"
        }
    }
}
