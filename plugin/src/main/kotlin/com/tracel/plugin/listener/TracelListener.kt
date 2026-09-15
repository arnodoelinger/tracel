package com.tracel.plugin.listener

import com.tracel.plugin.TracelServices
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.Listener

/**
 * Tracel listener abstract.
 *
 * - [shape] is world (blocks / entities, never items)
 * - [material] is lots between holders
 *
 * Delayed work must be [later] / [owing].
 *
 * Default `@Observes` is `MONITOR` + `ignoreCancelled`. Other priorities are
 * load-bearing.
 */
abstract class TracelListener(
    internal val services: TracelServices,
) : Listener {
    /** Shape capture. */
    protected val shape: ShapeCapture get() = services.shape

    /** Material capture. */
    protected val material: MaterialCapture get() = services.material

    /**
     * Rollback is rewriting the world.
     *
     * Those events are not history.
     */
    protected val restoring: Boolean get() = services.selfManagedWorld.isRestoring

    /** Schedules [work] on the region that owns [at] and makes flush wait for it. */
    protected fun later(at: Location, ticks: Long = 1L, work: () -> Unit) {
        services.pendingCaptures.owed()
        Bukkit.getRegionScheduler().runDelayed(services.plugin, at, {
            try {
                work()
            } finally {
                services.pendingCaptures.done()
            }
        }, ticks)
    }

    /**
     * Entity scheduler.
     *
     * `Folia` will not let another thread read that entity.
     */
    protected fun later(entity: Entity, ticks: Long = 1L, work: () -> Unit) {
        services.pendingCaptures.owed()
        entity.scheduler.runDelayed(services.plugin, {
            try {
                work()
            } finally {
                services.pendingCaptures.done()
            }
        }, { services.pendingCaptures.done() }, ticks)
    }

    /** Owing mechanism. Storage-thread work a flush must wait for. */
    protected fun owing(work: suspend () -> Unit) {
        services.pendingCaptures.owed()
        services.scope.launch { work() }.invokeOnCompletion { services.pendingCaptures.done() }
    }
}
