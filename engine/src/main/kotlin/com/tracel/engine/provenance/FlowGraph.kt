package com.tracel.engine.provenance

import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId

/**
 * Answers the two questions provenance exists for, by walking the
 * [LotEdge] graph a lot's history is made of:
 * backward through parents for "where did this come from", forward through
 * children for "what happened to this".
 *
 * The graph is a DAG by construction — an edge only ever points from an
 * older lot to a newer one, and lots are never edited after creation — so
 * this can never cycle. [maxDepth] is a defensive limit anyway: cheap
 * insurance against a future bug in how edges get recorded, not something a
 * correct history should ever actually hit.
 *
 * A walk is one hop onto the storage thread, not one per node.
 */
public class FlowGraph(private val repo: LotRepository, private val maxDepth: Int = 64) {
    /**
     * "Where did this item come from?" — walks parents back to the lots that had none,
     * i.e. the original mints.
     */
    public suspend fun originOf(lotId: LotId): ProvenanceNode = repo.reading { buildOrigin(lotId, depth = 0) }

    /** "What happened to this item?" — walks children forward to whatever is still live, or a sink. */
    public suspend fun fateOf(lotId: LotId): FateNode = repo.reading { buildFate(lotId, depth = 0) }

    private suspend fun buildOrigin(lotId: LotId, depth: Int): ProvenanceNode {
        val lot = repo.lot(lotId)
        val parents =
            if (depth >= maxDepth) emptyList() else repo.edgesInto(lotId).map { buildOrigin(it.parent, depth + 1) }
        return ProvenanceNode(lotId, lot.itemKey, lot.createdBy, parents)
    }

    private suspend fun buildFate(lotId: LotId, depth: Int): FateNode {
        val lot = repo.lot(lotId)
        val children =
            if (depth >= maxDepth) emptyList() else repo.edgesFrom(lotId).map { buildFate(it.child, depth + 1) }
        val holder = if (children.isEmpty()) repo.currentHolderOf(lotId) else null
        return FateNode(lotId, lot.itemKey, holder, children)
    }
}
