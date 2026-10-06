package com.tracel.engine.ledger.repository

import com.tracel.model.lot.LotEdge
import com.tracel.model.lot.LotId
import com.tracel.model.rollback.RollbackJobId

/** How lots became and begat one another: the edges of the lot graph. */
public interface LotEdges {
    /** Records that [edge] happened, linking a parent lot to a child lot. */
    public suspend fun recordEdge(edge: LotEdge)

    /** Forgets the edge from [parent] to [child]. */
    public suspend fun removeEdge(parent: LotId, child: LotId)

    /** Edges where [lotId] is the parent - how its life continued after creation. */
    public suspend fun edgesFrom(lotId: LotId): List<LotEdge>

    /**
     * The compensation [job] recorded for [originalLotId], if any — a point get, not a scan of
     * every outgoing edge.
     */
    public suspend fun findCompensateEdge(originalLotId: LotId, job: RollbackJobId): LotEdge.Compensate? {
        val edges = edgesFrom(originalLotId)
        var i = 0
        val n = edges.size
        while (i < n) {
            val edge = edges[i]
            if (edge is LotEdge.Compensate && edge.rollbackJob == job) return edge
            i++
        }
        return null
    }

    /**
     * [edgesFrom] for many lots in one snapshot. A rollback of a few thousand roots was opening
     * a prefix scan per lot in single file; the scans share nothing and run together.
     *
     * Every ID is present in the result, including lots with no outgoing edges.
     */
    public suspend fun edgesFromAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, List<LotEdge>>(ids.size)
        for (id in ids) out[id] = edgesFrom(id)
        return out
    }

    /** Edges where [lotId] is the child - how it came to exist. */
    public suspend fun edgesInto(lotId: LotId): List<LotEdge>

    /** [edgesInto] for many lots in one snapshot. Same reason as [edgesFromAll]. */
    public suspend fun edgesIntoAll(ids: Collection<LotId>): Map<LotId, List<LotEdge>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<LotId, List<LotEdge>>(ids.size)
        for (id in ids) out[id] = edgesInto(id)
        return out
    }
}
