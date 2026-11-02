package com.tracel.plugin.listener.material.item

import com.destroystokyo.paper.event.entity.ExperienceOrbMergeEvent
import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.plugin.bukkit.entity.remember
import com.tracel.plugin.bukkit.entity.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.bukkit.item.Experience
import org.bukkit.entity.ExperienceOrb
import org.bukkit.event.entity.EntityRemoveEvent
import org.bukkit.event.entity.EntitySpawnEvent

/** Experience orbs, booked just like ground items. */
class ExperienceListener(services: TracelServices) : TracelListener(services) {

    @Observes
    fun onSpawn(event: EntitySpawnEvent) {
        val orb = event.entity as? ExperienceOrb ?: return
        if (services.listeners.selfManagedSpawns.isSelfManagedSpawn || restoring) return
        services.listeners.groundWhereabouts.remember(orb)
        material.toppedUp(orb.holder(), Experience.KEY, orb.experience.toLong(), orb.toBlockPos())
    }

    @Observes
    fun onMerge(event: ExperienceOrbMergeEvent) {
        val source = event.mergeSource
        val target = event.mergeTarget
        services.listeners.selfManagedSpawns.forget(source.uniqueId)
        services.listeners.groundWhereabouts.remember(source)
        services.listeners.groundWhereabouts.remember(target)
        material.releasedPile(
            CauseKind.NATURAL, null, Experience.KEY,
            source.holder(), source.experience.toLong(),
            target.holder(), target.experience.toLong(),
            source.toBlockPos(),
        )
    }

    @Observes(priority = Priority.HIGHEST)
    fun holdPickupWhileRestoring(event: PlayerPickupExperienceEvent) {
        if (services.listeners.frozen.isFrozen(event.experienceOrb.holder())) event.isCancelled = true
    }

    @Observes
    fun onPickup(event: PlayerPickupExperienceEvent) {
        val orb = event.experienceOrb
        val points = orb.experience.toLong()
        if (points <= 0L) return
        services.listeners.groundWhereabouts.remember(orb)
        val at = orb.toBlockPos()
        val player = HolderId.Player(event.player.uniqueId)
        material.movedFromPile(CauseKind.PLAYER_ACTION, player, Experience.KEY, orb.holder(), player, points, at)
    }

    @Observes(ignoreCancelled = false)
    fun onRemove(event: EntityRemoveEvent) {
        val orb = event.entity as? ExperienceOrb ?: return
        if (event.cause != EntityRemoveEvent.Cause.UNLOAD) services.listeners.selfManagedSpawns.forget(orb.uniqueId)
        if (restoring) return
        val sink = sinkFor(event.cause) ?: return
        services.listeners.groundWhereabouts.remember(orb)
        material.releasedPile(
            CauseKind.NATURAL, null, Experience.KEY,
            orb.holder(), orb.experience.toLong(),
            HolderId.Sink(sink), 0L,
            orb.toBlockPos(),
        )
    }

    private fun ExperienceOrb.holder() = HolderId.ItemEntity(uniqueId)

    private fun sinkFor(cause: EntityRemoveEvent.Cause): SinkKind? = when (cause) {
        EntityRemoveEvent.Cause.DESPAWN -> SinkKind.DESPAWN

        EntityRemoveEvent.Cause.EXPLODE,
        EntityRemoveEvent.Cause.HIT,
        EntityRemoveEvent.Cause.ENTER_BLOCK,
        EntityRemoveEvent.Cause.OUT_OF_WORLD,
        EntityRemoveEvent.Cause.DISCARD,
        EntityRemoveEvent.Cause.DEATH,
            -> SinkKind.UNATTRIBUTED

        else -> null
    }
}
