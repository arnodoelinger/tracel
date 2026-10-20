package com.tracel.plugin.api.rollback

import com.tracel.api.actor.Actor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.RecordKind
import com.tracel.api.result.Res
import com.tracel.api.result.getOrElse
import com.tracel.api.rollback.BorrowedArea
import com.tracel.api.rollback.ConflictPolicy
import com.tracel.api.rollback.ItemBalance
import com.tracel.api.rollback.RollbackApplied
import com.tracel.api.rollback.RollbackDraft
import com.tracel.api.rollback.RollbackError
import com.tracel.api.rollback.RollbackReady
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.rollback.Rollbacks
import com.tracel.api.world.LookupArea
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.holder.HolderId
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.api.read.MaterialResolver
import com.tracel.plugin.command.args.scope.ScopeLimits
import com.tracel.plugin.command.args.support.MaterialAliases
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Rollbacks] for one plugin, over the same planner and composer `/tracel rollback` uses. Every job it runs is
 * signed with [signer], so lookups and undo know whose it was.
 */
internal open class TracelRollbacks(
    private val services: TracelServices,
    private val signer: String,
    private val alive: () -> Boolean,
    private val borrows: Borrows,
    private val lease: Borrows.Lease? = null,
    private val materials: MaterialResolver = MaterialAliases::resolve,
) : Rollbacks {
    private val by = HolderId.Plugin(signer)

    override fun Player.rollback(build: HistoryQuery.Builder.() -> Unit): RollbackDraft =
        rollback(HistoryQuery(by = setOf(Actor.Player(uniqueId))).with(build))

    override fun LookupArea.rollback(build: HistoryQuery.Builder.() -> Unit): RollbackDraft =
        rollback(HistoryQuery(area = this).with(build))

    override fun rollback(query: HistoryQuery): RollbackDraft = Draft(query, ConflictPolicy.SKIP, mintAllowed = false)

    override suspend fun <T> borrow(area: LookupArea, block: suspend BorrowedArea.() -> T): Res<T, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        lease?.let { held -> if (!Borrows.within(area, held.area)) return Res.Err(RollbackError.OutsideBorrow) }
        val taken = borrows.take(signer, area).getOrElse { return Res.Err(it) }
        return try {
            Res.Ok(Borrowed(taken).block())
        } finally {
            borrows.release(taken)
        }
    }

    override suspend fun undo(job: Long): Res<RollbackUndone, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        return gated { undoGated(RollbackJobId(job)) }
    }

    private fun refusal(): RollbackError? = when {
        !alive() -> RollbackError.Revoked
        lease?.expired == true -> RollbackError.Expired
        else -> null
    }

    private suspend fun <T> gated(work: suspend () -> Res<T, RollbackError>): Res<T, RollbackError> {
        if (!services.composite.claimGate()) return Res.Err(RollbackError.Busy)
        return try {
            services.purgeGate.awaitSlice()
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Res.Err(RollbackError.Failed(failure.message ?: failure.javaClass.simpleName))
        } finally {
            services.composite.releaseGate()
        }
    }

    private fun admit(query: HistoryQuery): Res<LookupFilter, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        if (query.since == null) return Res.Err(RollbackError.Unbounded("time"))
        val area = query.area ?: return Res.Err(RollbackError.Unbounded("area"))
        val radius = when (area) {
            is LookupArea.Around -> area.radius
            is LookupArea.Cube -> maxOf(area.maxX - area.minX, area.maxZ - area.minZ) / 2
            is LookupArea.World -> return Res.Err(RollbackError.Unbounded("an area smaller than a world."))
        }
        ScopeLimits.rollbackMaxBlocks?.let { max ->
            if (radius > max) return Res.Err(RollbackError.OverBudget("radius", max.toLong(), radius.toLong()))
        }
        lease?.let { held -> if (!Borrows.within(area, held.area)) return Res.Err(RollbackError.OutsideBorrow) }
        borrows.heldAgainst(signer, area)?.let { return Res.Err(RollbackError.Borrowed(it)) }
        return ApiConversions.rollbackFilter(query, materials)
            .let { got -> got.getOrElse { return Res.Err(RollbackError.Unsupported(it.toString())) } }
            .let { Res.Ok(it) }
    }

    private suspend fun plan(query: HistoryQuery, filter: LookupFilter): Planned = services.composite.plan(
        filter,
        structure = RecordKind.BLOCK in query.kinds || RecordKind.ENTITY in query.kinds,
        material = RecordKind.ITEM in query.kinds,
    )

    private suspend fun undoGated(job: RollbackJobId): Res<RollbackUndone, RollbackError> {
        if (!services.composite.isUndoable(by, job)) return Res.Err(RollbackError.NotUndoable)
        return when (val outcome = services.composite.undo(job)) {
            is UndoResult.Done -> Res.Ok(
                RollbackUndone(
                    job.raw,
                    outcome.structure.count,
                    outcome.structure.skipped.size,
                    outcome.material.fullyRestored,
                ),
            )
            UndoResult.NotFound, UndoResult.AlreadyUndone -> Res.Err(RollbackError.NotUndoable)
            is UndoResult.OutOfOrder -> Res.Err(RollbackError.OutOfOrder(outcome.newer.map { it.raw }))
            is UndoResult.Failed -> Res.Err(RollbackError.Failed(outcome.reason))
            is Unreachable -> Res.Err(unreachable(outcome))
            is Blocked -> Res.Err(RollbackError.Blocked)
        }
    }

    private fun unreachable(outcome: Unreachable): RollbackError = when (val holder = outcome.holder) {
        is HolderId.Custom -> RollbackError.AddonMissing(ApiConversions.kind(holder.kind))
        else -> RollbackError.Unreachable(outcome.reason)
    }

    private inner class Draft(
        override val query: HistoryQuery,
        override val conflicts: ConflictPolicy,
        override val mintAllowed: Boolean,
    ) : RollbackDraft {
        override fun onConflict(policy: ConflictPolicy): RollbackDraft = Draft(query, policy, mintAllowed)

        override fun allowMint(): RollbackDraft = Draft(query, conflicts, mintAllowed = true)

        override suspend fun preview(): Res<RollbackReady, RollbackError> {
            val filter = admit(query).getOrElse { return Res.Err(it) }
            return gated {
                Res.Ok(Ready(this, filter, services.composite.holdingStill(filter.region) { plan(query, filter) }))
            }
        }
    }

    private inner class Ready(
        override val draft: Draft,
        private val filter: LookupFilter,
        private val planned: Planned,
    ) : RollbackReady {
        private val used = AtomicBoolean(false)
        private val composite = planned.composite
        private val material = composite.material

        override val blocks: Int get() = composite.structureCount
        override val entities: Int
            get() = composite.create.count { it is StructureStep.SpawnEntity && it.expected == null }
        override val balance: ItemBalance
            get() = ItemBalance(material.takeCount, material.unmakeCount, material.mintCount)
        override val complete: Boolean get() = planned.flushed
        override val imported: Int get() = planned.imported
        override val isEmpty: Boolean get() = composite.isEmpty

        override suspend fun apply(): Res<RollbackApplied, RollbackError> {
            if (!used.compareAndSet(false, true)) return Res.Err(RollbackError.Moved)
            admit(draft.query).getOrElse { return Res.Err(it) }
            if (isEmpty) return Res.Err(RollbackError.NothingToDo)
            budget()?.let { return Res.Err(it) }
            if (balance.minted > 0 && !draft.mintAllowed) return Res.Err(RollbackError.WouldMint(balance.minted))
            val got = gated { applyGated() }
            if (got is Res.Err && got.error == RollbackError.Busy) used.set(false)
            return got
        }

        private fun budget(): RollbackError? = when {
            entities > services.entityRestoreLimit ->
                RollbackError.OverBudget("entities", services.entityRestoreLimit.toLong(), entities.toLong())
            else -> null
        }

        private suspend fun applyGated(): Res<RollbackApplied, RollbackError> =
            services.composite.holdingStill(filter.region) {
                val strict = draft.conflicts == ConflictPolicy.SKIP
                when (val outcome = services.composite.apply(planned.copy(by = by), strict)) {
                    is RollbackResult.Done -> {
                        val items = material.takeCount + material.unmakeCount + material.mintCount
                        if (outcome.structure.count == 0 && items == 0) {
                            services.atomically { services.jobs.markUndone(outcome.job) }
                            services.rolledBack.restore(outcome.job.raw)
                        }
                        Res.Ok(Applied(outcome))
                    }
                    RollbackResult.Stale ->
                        Res.Err(RollbackError.Stale(Ready(draft, filter, plan(draft.query, filter))))
                    is Unreachable -> Res.Err(unreachable(outcome))
                    is Blocked -> Res.Err(RollbackError.Blocked)
                }
            }
    }

    private inner class Applied(done: RollbackResult.Done) : RollbackApplied {
        private val used = AtomicBoolean(false)

        override val job: Long = done.job.raw
        override val changed: Int = done.structure.count
        override val skipped: Int = done.structure.skipped.size
        override val overwritten: Int = done.structure.overwritten
        override val itemsRestored: Boolean = done.material.fullyRestored

        override suspend fun undo(): Res<RollbackUndone, RollbackError> {
            if (!used.compareAndSet(false, true)) return Res.Err(RollbackError.Moved)
            refusal()?.let { return Res.Err(it) }
            val got = gated { undoGated(RollbackJobId(job)) }
            if (got is Res.Err && got.error == RollbackError.Busy) used.set(false)
            return got
        }
    }

    private inner class Borrowed(private val held: Borrows.Lease) :
        TracelRollbacks(services, signer, alive, borrows, held, materials), BorrowedArea {
        override val area: LookupArea get() = held.area
        override val isExpired: Boolean get() = held.expired
    }
}
