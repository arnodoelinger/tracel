package com.tracel.plugin.api.addon

import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.InspectLines
import com.tracel.api.addon.OnInspect
import com.tracel.api.addon.OnRecord
import com.tracel.api.addon.OnRollbackFinished
import com.tracel.api.addon.OnRollbackStarted
import com.tracel.api.addon.OnUndo
import com.tracel.api.addon.RollbackJob
import com.tracel.api.addon.RollbackOutcome
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.RecordKind
import com.tracel.api.rollback.RollbackUndone
import com.tracel.api.world.BlockPlace
import java.util.logging.Level
import java.util.logging.Logger

/** Turns an addon's annotated methods into what it is subscribed to. */
internal fun interface AddonBinder {
    /**
     * What [addon] needs bound on every enable, or `null` when it has nothing for this binder.
     *
     * Runs at registration, so a bad signature throws there, before the addon ever starts.
     */
    fun prepare(addon: Any): AddonBinding?
}

/** Subscriptions to make for one enable; the context owns them, so they end with it. */
internal fun interface AddonBinding {
    fun bind(context: AddonContext)
}

/** Records to hear, [only] these kinds or every one when empty; closing ends it. */
internal typealias RecordFeed = (only: Set<RecordKind>, sink: (HistoryRecord) -> Unit) -> AutoCloseable

/** Binds `@OnRecord`, `@OnRollbackStarted`, `@OnRollbackFinished`, `@OnUndo` and `@OnInspect`. */
internal class HandlerBinder(
    private val extensions: Extensions,
    private val records: RecordFeed,
    private val logger: Logger,
) : AddonBinder {
    override fun prepare(addon: Any): AddonBinding? {
        val recorded = findAnnotated(addon, OnRecord::class.java)
        val onStarted = findAnnotated(addon, OnRollbackStarted::class.java).map { it.first }
        val onFinished = findAnnotated(addon, OnRollbackFinished::class.java).map { it.first }
        val onUndo = findAnnotated(addon, OnUndo::class.java).map { it.first }
        val inspected = findAnnotated(addon, OnInspect::class.java).map { it.first }

        recorded.forEach { (method, _) -> method.requireParameters("OnRecord", HistoryRecord::class.java) }
        onStarted.forEach { it.requireParameters("OnRollbackStarted", RollbackJob::class.java) }
        onFinished.forEach {
            it.requireParameters("OnRollbackFinished", RollbackJob::class.java, RollbackOutcome::class.java)
        }
        onUndo.forEach { it.requireParameters("OnUndo", RollbackUndone::class.java) }
        inspected.forEach { it.requireParameters("OnInspect", BlockPlace::class.java, InspectLines::class.java) }

        val rollbacks = onStarted.isNotEmpty() || onFinished.isNotEmpty() || onUndo.isNotEmpty()
        if (recorded.isEmpty() && !rollbacks && inspected.isEmpty()) return null

        return AddonBinding { context ->
            for ((method, marked) in recorded) {
                context.own(records(marked.only.toSet()) { record -> safely(method) { call(record) } })
            }
            if (rollbacks) context.own(extensions.rollbacks.add(object : RollbackHearer {
                override fun started(job: RollbackJob) = onStarted.forEach { safely(it) { call(job) } }

                override fun finished(job: RollbackJob, outcome: RollbackOutcome) =
                    onFinished.forEach { safely(it) { call(job, outcome) } }

                override fun undone(undone: RollbackUndone) = onUndo.forEach { safely(it) { call(undone) } }
            }))
            if (inspected.isNotEmpty()) context.own(extensions.inspectors.add { place, lines ->
                inspected.forEach { safely(it) { call(place, lines) } }
            })
        }
    }

    private inline fun safely(method: AddonMethod, work: AddonMethod.() -> Unit) {
        try {
            method.work()
        } catch (failure: Throwable) {
            logger.log(Level.SEVERE, "Addon handler ${method.description} threw.", failure)
        }
    }
}
