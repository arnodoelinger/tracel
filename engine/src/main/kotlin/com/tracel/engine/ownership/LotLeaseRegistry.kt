package com.tracel.engine.ownership

import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId

/**
 * Reserves lots for the duration of a rollback job — the runtime half of the ownership story
 * ([LotLease] is the compile-time half). Two admins running `/tracel rollback apply` over
 * overlapping territory at the same time must not both win: whichever calls [acquire] first
 * gets every lot it asked for, and the second gets told exactly which ones are already spoken
 * for, instead of both silently racing to mutate the same lots.
 *
 * [acquire] is deliberately not `open`: it is the only code path anywhere that calls
 * [LotLease.Companion.mint], so no subclass — in this module or a dependent one like
 * `com.tracel.storage` — can hand out a lease without actually reserving anything first.
 * Subclasses only ever implement the storage primitives.
 */
public abstract class LotLeaseRegistry {
    /**
     * Attempts to reserve every lot in [lotIds] for [job], all or nothing. Reserving lots the
     * same [job] already holds is a no-op, not a conflict — a job is allowed to re-acquire its
     * own lease (after a crash and restart, for instance), and doing so renews every lot it
     * already held, which is what [reapAbandoned] uses to tell a job that is still actively
     * working from one that has gone silent.
     */
    public fun acquire(job: RollbackJobId, lotIds: Set<LotId>): LeaseAcquisition {
        val conflicts = tryReserve(job, lotIds)
        return if (conflicts.isEmpty()) {
            LeaseAcquisition.Granted(LotLease.mint(job, lotIds))
        } else {
            LeaseAcquisition.Denied(conflicts)
        }
    }

    /**
     * Grows [lease] to also cover [additionalLotIds] — for a job that started with lot X and
     * only discovered mid-flight that it also needs lot Y. All-or-nothing exactly like
     * [acquire]: on [LeaseAcquisition.Denied], [lease] itself is untouched and still valid for
     * whatever it already covered.
     */
    public fun extend(lease: LotLease, additionalLotIds: Set<LotId>): LeaseAcquisition =
        acquire(lease.job, lease.lotIds + additionalLotIds)

    /** Releases every lot [job] holds. Idempotent — releasing a job that holds nothing is a no-op. */
    public abstract fun release(job: RollbackJobId)

    /**
     * Atomically reassigns every lot [from] currently holds to [to] — no window where the lots
     * are held by neither, unlike calling [release] followed by [acquire] would leave.
     *
     * @return the lots that were transferred.
     */
    public abstract fun transfer(from: RollbackJobId, to: RollbackJobId): Set<LotId>

    /**
     * Releases every lease whose most recent [acquire] / [extend] is older than [maxAgeMillis]
     * relative to [nowMillis] — a job that crashed and never came back to resume must not hold
     * its lots forever. Nothing here decides whether a job counts as abandoned on its own;
     * the caller supplies [nowMillis] and [maxAgeMillis] and is expected to log or alert on
     * whatever comes back, since silently forgetting an abandoned lock is exactly the failure
     * mode this exists to avoid.
     *
     * @return the jobs that were reaped.
     */
    public abstract fun reapAbandoned(nowMillis: Long, maxAgeMillis: Long): Set<RollbackJobId>

    /**
     * Reserves [lotIds] for [job] if and only if none of them are already held by a
     * different job — an all-or-nothing check-then-set, run under whatever this
     * implementation's own single-writer discipline is.
     *
     * @return the lots that were already held by someone else, mapped to who holds them.
     * Empty means the reservation succeeded and every lot in [lotIds] is now held by [job].
     */
    protected abstract fun tryReserve(job: RollbackJobId, lotIds: Set<LotId>): Map<LotId, RollbackJobId>
}
