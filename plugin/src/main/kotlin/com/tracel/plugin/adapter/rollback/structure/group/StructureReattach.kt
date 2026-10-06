package com.tracel.plugin.adapter.rollback.structure.group

import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.leashHolder
import com.tracel.model.world.entity.vehicle
import com.tracel.plugin.adapter.entity.link.applyLeash
import com.tracel.plugin.adapter.entity.link.applyVehicle
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.log.Warnings
import org.bukkit.entity.Entity

/** Other region's spawn should be done. */
private const val REATTACH_DELAY_TICKS = 5L

/** Link an entity to its leash holder and vehicle. */
internal fun Entity.linkedAsRecorded(shape: EntityShape): Boolean {
    val tied = applyLeash(shape.extras.leashHolder)
    val seated = applyVehicle(shape.extras.vehicle)
    return tied && seated
}

/** Link an entity to its leash holder and vehicle. */
internal fun StructureRestorer.reattachLater(
    hull: Entity,
    shape: EntityShape,
) {
    runCatching {
        hull.scheduler.runDelayed(services.plugin, {
            if (!hull.isValid || hull.linkedAsRecorded(shape)) return@runDelayed

            // Do not cut a working lead because a seat is still missing
            Warnings.once(logger, "link:${hull.type}") {
                val holder = shape.extras.leashHolder ?: shape.extras.vehicle
                "restored a ${hull.type.name.lowercase()} whose leash holder or vehicle " +
                        "($holder) was not put back in time — it keeps whatever link it has"
            }
        }, {}, REATTACH_DELAY_TICKS)
    }
}
