package com.tracel.plugin.api.rollback

import com.tracel.plugin.actor.ActorHolders
import com.tracel.api.actor.Actor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.RecordKind
import com.tracel.api.item.ItemHolder
import com.tracel.api.result.Res
import com.tracel.api.result.getOrElse
import com.tracel.api.rollback.BorrowedArea
import com.tracel.api.rollback.ConflictPolicy
import com.tracel.api.rollback.ItemBalance
import com.tracel.api.rollback.RollbackApplied
import com.tracel.api.rollback.RollbackDraft
import com.tracel.api.rollback.RollbackError
import com.tracel.api.rollback.RollbackJobId
import com.tracel.api.rollback.PlannedBlock
import com.tracel.api.rollback.UndoPreview
import com.tracel.api.world.NamespacedName
import com.tracel.api.rollback.RollbackReady
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.rollback.Rollbacks
import com.tracel.api.world.LookupArea
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.engine.rollback.structure.inverse
import com.tracel.model.world.TILE_SHIFT
import com.tracel.plugin.adapter.rollback.composer.warmForPreview
import com.tracel.plugin.specifics.block.isAirLike
import com.tracel.model.holder.HolderId
import com.tracel.model.cause.CauseKind as ModelCause
import com.tracel.model.rollback.RollbackJobId as ModelJob
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.api.read.MaterialResolver
import com.tracel.plugin.config.ScopeLimits
import com.tracel.plugin.api.read.MaterialAliases
import com.tracel.plugin.rollback.result.outcome.Blocked
import com.tracel.plugin.rollback.result.outcome.Planned
import com.tracel.plugin.rollback.result.outcome.RollbackResult
import com.tracel.plugin.rollback.result.outcome.UndoResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [Rollbacks] for one plugin, or for the commands: `/tracel rollback` and its undo run through these too, signed as
 * the player who typed them. Every job it runs is signed with [by], so lookups and undo know whose it was, and holds
 * areas as [signer].
 *
 * [confirmed] is for the commands: the sender has said yes to bringing back more entities than the limit.
 */
internal open class TracelRollbacks(
    private val services: TracelServices,
    private val signer: String,
    private val alive: () -> Boolean,
    private val borrows: Borrows,
    private val lease: Borrows.Lease? = null,
    private val materials: MaterialResolver = MaterialAliases::resolve,
    private val by: HolderId? = HolderId.Plugin(signer),
    private val confirmed: Boolean = false,
) : Rollbacks {
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

    override suspend fun undo(job: RollbackJobId): Res<RollbackUndone, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        return gated { undoGated(ModelJob(job.raw)) }
    }

    override suspend fun undoPreview(job: RollbackJobId): Res<UndoPreview, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        if (!services.composite.isUndoable(by, ModelJob(job.raw))) return Res.Err(RollbackError.NotUndoable)
        val record = services.atomically { services.jobs.find(ModelJob(job.raw)) }
            ?: return Res.Err(RollbackError.NotUndoable)
        return Res.Ok(UndoPreview(job, entityTypes((record.create + record.destroy).map { it.inverse() })))
    }

    override suspend fun latest(): RollbackJobId? =
        services.composite.lastUndoable(by)?.let { RollbackJobId(it.raw) }

    override suspend fun <T> hold(area: LookupArea?, block: suspend () -> T): Res<T, RollbackError> {
        refusal()?.let { return Res.Err(it) }
        val region = area?.let(ApiConversions::region)
        return gated { Res.Ok(services.composite.holdingStill(region) { withContext(Held) { block() } }) }
    }

    private fun refusal(): RollbackError? = when {
        !alive() -> RollbackError.Revoked
        lease?.expired == true -> RollbackError.Expired
        else -> null
    }

    private object Held : AbstractCoroutineContextElement(Key), CoroutineContext.Element {
        object Key : CoroutineContext.Key<Held>
    }

    private suspend fun <T> still(region: LookupRegion?, work: suspend () -> T): T =
        if (coroutineContext[Held.Key] != null) work() else services.composite.holdingStill(region, work)

    private suspend fun <T> gated(work: suspend () -> Res<T, RollbackError>): Res<T, RollbackError> {
        if (coroutineContext[Held.Key] != null) return work()
        if (!services.composite.claimGate()) return Res.Err(RollbackError.Busy)
        return try {
            services.purgeGate.awaitSlice()
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Telemetry.error(failure)
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
            is LookupArea.Chunks -> area.radius shl TILE_SHIFT
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

    private suspend fun undoGated(job: ModelJob): Res<RollbackUndone, RollbackError> {
        if (!services.composite.isUndoable(by, job)) return Res.Err(RollbackError.NotUndoable)
        return when (val outcome = services.composite.undo(job)) {
            is UndoResult.Done -> Res.Ok(
                RollbackUndone(
                    RollbackJobId(job.raw),
                    outcome.structure.count,
                    outcome.structure.skipped.size,
                    outcome.material.fullyRestored,
                ),
            )
            UndoResult.NotFound, UndoResult.AlreadyUndone -> Res.Err(RollbackError.NotUndoable)
            is UndoResult.OutOfOrder -> Res.Err(RollbackError.OutOfOrder(outcome.newer.map { RollbackJobId(it.raw) }))
            is UndoResult.Failed -> Res.Err(RollbackError.Failed(outcome.reason))
            is Unreachable -> Res.Err(unreachable(outcome))
            is Blocked -> Res.Err(blocked(outcome))
        }
    }

    private fun unreachable(outcome: Unreachable): RollbackError = when (val holder = outcome.holder) {
        is HolderId.Custom ->
            RollbackError.AddonMissing(ItemHolder.Custom(ApiConversions.kind(holder.kind), holder.id), outcome.reason)
        else -> RollbackError.Unreachable(ActorHolders.holder(holder), outcome.reason)
    }

    private suspend fun blocked(outcome: Blocked): RollbackError.Blocked {
        val holds = outcome.conflicts.values.groupingBy { it }.eachCount().map { (job, lots) ->
            val owner = services.atomically { services.jobs.find(job) }?.by
            RollbackError.Blocked.Hold(RollbackJobId(job.raw), ActorHolders.actor(owner, ModelCause.UNKNOWN), lots)
        }
        return RollbackError.Blocked(holds.sortedBy { it.job })
    }

    private fun entityTypes(steps: List<StructureStep>): Map<NamespacedName, Int> =
        steps.filterIsInstance<StructureStep.SpawnEntity>().filter { it.expected == null }
            .groupingBy { ApiConversions.kind(it.shape.type.value) }.eachCount()

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
                Res.Ok(Ready(this, filter, still(filter.region) { plan(query, filter) }))
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
        override val restores: Int get() = composite.create.size
        override val removals: Int get() = composite.destroy.size
        override val entities: Int
            get() = composite.create.count { it is StructureStep.SpawnEntity && it.expected == null }
        override val entityTypes: Map<NamespacedName, Int> get() = entityTypes(composite.create)
        override val blockChanges: List<PlannedBlock>
            get() = (composite.create.asSequence() + composite.destroy.asSequence())
                .filterIsInstance<StructureStep.SetBlock>()
                .map { PlannedBlock(ApiConversions.place(it.at), it.target.data.value, it.expected.data.value) }
                .toList()
        override val balance: ItemBalance
            get() = ItemBalance(material.takeCount, material.unmakeCount, material.mintCount)
        override val complete: Boolean get() = planned.flushed
        override val imported: Int get() = planned.imported
        override val outside: Int get() = planned.outside.size
        override val isEmpty: Boolean get() = composite.isEmpty && planned.outside.isEmpty()

        override suspend fun warm() = warmForPreview(services, planned)

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
            !confirmed && entities > services.entityRestoreLimit ->
                RollbackError.OverBudget("entities", services.entityRestoreLimit.toLong(), entities.toLong())
            else -> null
        }

        private suspend fun applyGated(): Res<RollbackApplied, RollbackError> =
            still(filter.region) {
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
                    is Blocked -> Res.Err(blocked(outcome))
                }
            }
    }

    private inner class Applied(done: RollbackResult.Done) : RollbackApplied {
        private val used = AtomicBoolean(false)

        override val job: RollbackJobId = RollbackJobId(done.job.raw)
        override val changed: Int = done.structure.count
        override val removed: Int = done.structure.applied.count {
            it is StructureStep.RemoveEntity || (it is StructureStep.SetBlock && it.target.isAirLike)
        }
        override val restored: Int = changed - removed
        override val balance: ItemBalance = done.plan.composite.material.let {
            ItemBalance(it.takeCount, it.unmakeCount, it.mintCount)
        }
        override val outside: Int = done.plan.outside.size
        override val records: Int = done.plan.taken.size
        override val skipped: Int = done.structure.skipped.size
        override val overwritten: Int = done.structure.overwritten
        override val itemsRestored: Boolean = done.material.fullyRestored

        override suspend fun undo(): Res<RollbackUndone, RollbackError> {
            if (!used.compareAndSet(false, true)) return Res.Err(RollbackError.Moved)
            refusal()?.let { return Res.Err(it) }
            val got = gated { undoGated(ModelJob(job.raw)) }
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
