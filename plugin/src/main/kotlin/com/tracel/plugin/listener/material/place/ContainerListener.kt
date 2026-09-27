package com.tracel.plugin.listener.material.place

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.block.*
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.lectern.LecternPages
import com.tracel.plugin.listener.world.entity.ExplosionListener
import org.bukkit.block.Lectern
import org.bukkit.block.ShulkerBox
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent

/**
 * Container break listener.
 *
 * Player [BlockBreakEvent] only. Explosions empty cargo in [ExplosionListener].
 */
class ContainerListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onTakeBook(event: PlayerTakeLecternBookEvent) {
        LecternPages.left(event.lectern.block.toBlockPos(), event.lectern.page)
    }

    @Observes
    fun onBreak(event: BlockBreakEvent) {
        (event.block.getState(false) as? Lectern)?.let { LecternPages.left(event.block.toBlockPos(), it.page) }
        if (event.block.state is ShulkerBox) {
            packShulker(event)
            return
        }
        val slots = event.block.cargoSlots() ?: return
        val holder = event.block.toHolderId()
        val causedBy = HolderId.Player(event.player.uniqueId)
        val epochMillis = System.currentTimeMillis()
        val dropLocation = event.block.location.add(0.5, 0.5, 0.5)

        // Where each stack lay
        material.captureSlotLayout(holder, slots)

        // Clear now: the ledger read is too late for vanilla's spill.
        // Read first; respawning only ledgered lots deleted chests nobody had opened since plugin start.
        val removed = slots.takeAll()
        event.block.resyncCargo()

        val movesTo = event.block.accountMovesTo()
        material.dropping(
            holder = holder,
            at = dropLocation,
            cause = CauseKind.BLOCK_BREAK,
            causedBy = causedBy,
            epochMillis = epochMillis,
            removed = removed,
            andThen = movesTo?.let { to -> { material.relocate(holder, to) } },
        )
    }

    private fun packShulker(event: BlockBreakEvent) {
        val drop = runCatching { event.block.getDrops(event.player.inventory.itemInMainHand, event.player) }.getOrNull()
            ?.firstOrNull { it.type == event.block.type } ?: return
        material.packedShulker(event.block, drop, CauseKind.BLOCK_BREAK, HolderId.Player(event.player.uniqueId))
    }
}
