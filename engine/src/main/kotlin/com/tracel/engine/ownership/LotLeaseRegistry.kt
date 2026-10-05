package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/** Who is allowed to touch which lots. */
public abstract class LotLeaseRegistry {
    /** Reserves every lot in [lotIds] for [job], or nothing at all. */
    public abstract suspend fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId>

    /** Gives back everything [job] holds. */
    public abstract suspend fun release(job: RollbackJobId)

    /**
     * Hands everything [from] holds to [to], in one step.
     *
     * @return the lots that moved.
     */
    public abstract suspend fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId>

    /**
     * Frees every lease older than [maxAgeMillis] as of [nowMillis].
     *
     * The backstop for a job that died with the process still holding its lots.
     *
     * @return the jobs that were reaped.
     */
    public abstract suspend fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId>

    /** Asks for [lotIds] on behalf of [job]. Granted or denied whole. */
    public suspend fun acquire(job: RollbackJobId, lotIds: Set<LotId>): LeaseAcquisition = reserve(job, lotIds)

    /** Grows [lease] to cover [lotIds] as well. */
    public suspend fun extend(lease: LotLease, lotIds: Set<LotId>): LeaseAcquisition =
        reserve(lease.job, lease.lotIds + lotIds)

    /** Runs [block] and gives [job]'s lots back when it is over. */
    public suspend fun <R> holdingFor(job: RollbackJobId, block: suspend () -> R): R {
        val held = try {
            block()
        } catch (failure: Throwable) {
            if (failure !is KeepsLease) release(job)
            throw failure
        }
        release(job)
        return held
    }

    private suspend fun reserve(job: RollbackJobId, lotIds: Set<LotId>): LeaseAcquisition {
        val conflicts = tryReserve(job, lotIds)
        return if (conflicts.isEmpty()) {
            LeaseAcquisition.Granted(LotLease(job, lotIds))
        } else {
            LeaseAcquisition.Denied(conflicts)
        }
    }
}
