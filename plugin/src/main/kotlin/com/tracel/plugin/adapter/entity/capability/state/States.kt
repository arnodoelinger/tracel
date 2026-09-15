package com.tracel.plugin.adapter.entity.capability.state

import com.tracel.annotations.Unstable
import com.tracel.plugin.adapter.entity.special.ArmorStandPose
import com.tracel.plugin.adapter.entity.special.ItemFrameState
import com.tracel.plugin.adapter.entity.special.PaintingArt
import org.bukkit.entity.Entity

/** Every in-place setter, in an order that does not fight itself. */
@Unstable
internal object InPlaceStates {
    // Warning: do not change the order
    private val all: List<InPlaceState> = listOf(
        BaseEntityState,
        HangingState,
        ItemFrameState,
        PaintingArt,
        ArmorStandPose,
        LivingState,
        AgeableState,
        TameableState,
        SittableState,
        CollarState,
        WornEquipmentState,
    )

    fun apply(live: Entity, ghost: Entity) {
        for (state in all) state.apply(live, ghost)
    }
}
