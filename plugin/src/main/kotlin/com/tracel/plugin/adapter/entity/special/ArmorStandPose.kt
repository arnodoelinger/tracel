package com.tracel.plugin.adapter.entity.special

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.capability.state.InPlaceState
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity

/**
 * Armor stand poses.
 *
 * @see ArmorStand
 */
@Unstable
internal object ArmorStandPose : InPlaceState {
    override fun apply(live: Entity, ghost: Entity) {
        if (live !is ArmorStand || ghost !is ArmorStand) return
        live.headPose = ghost.headPose
        live.bodyPose = ghost.bodyPose
        live.leftArmPose = ghost.leftArmPose
        live.rightArmPose = ghost.rightArmPose
        live.leftLegPose = ghost.leftLegPose
        live.rightLegPose = ghost.rightLegPose
        live.isSmall = ghost.isSmall
        live.setArms(ghost.hasArms())
        live.setBasePlate(ghost.hasBasePlate())
        live.isMarker = ghost.isMarker
        live.isInvisible = ghost.isInvisible
    }
}
