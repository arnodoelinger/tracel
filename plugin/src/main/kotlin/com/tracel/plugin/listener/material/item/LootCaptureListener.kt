package com.tracel.plugin.listener.material.item

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.block.toHolderId
import com.tracel.plugin.adapter.item.toHolderId
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.drop.BlockRelease
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockDispenseLootEvent
import org.bukkit.event.world.LootGenerateEvent

/** Loot event listener. */
class LootCaptureListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onGenerate(event: LootGenerateEvent) {
        val inventory = event.inventoryHolder?.inventory ?: return
        val holder = inventory.toHolderId() ?: return
        val totals = event.loot.toItemTotals()
        if (totals.isEmpty()) return
        val by = (event.entity as? Player)?.let { HolderId.Player(it.uniqueId) }
        val at = inventory.location?.block?.toBlockPos()
        material.minted(
            totals = totals,
            into = holder,
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by,
            at = at,
        )
    }

    @Observes
    fun onDispenseLoot(event: BlockDispenseLootEvent) {
        val totals = event.dispensedLoot.toItemTotals()
        if (totals.isEmpty()) return
        val block = event.block
        val by = event.player?.let { HolderId.Player(it.uniqueId) }
        material.releasing(
            releases = listOf(BlockRelease(block.toHolderId(), block.world, block.x, block.y, block.z, totals)),
            cause = if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD,
            causedBy = by,
            at = block.toBlockPos(),
        )
    }
}
