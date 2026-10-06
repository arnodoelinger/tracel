package com.tracel.plugin.adapter.rollback.material.holder

import com.tracel.plugin.adapter.item.openGrid
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.transientInputSlots
import com.tracel.plugin.adapter.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.MaterialRestorer
import org.bukkit.entity.Player

/** Take remaining [Moves.owed] from the cursor. */
internal fun MaterialRestorer.takeFromCursor(player: Player, moves: Moves) {
    if (moves.shortfalls <= 0L) return
    val cursor = player.itemOnCursor
    if (cursor.isEmpty || cursor.type.isAir) return
    val key = cursor.toItemKey()
    val owed = moves.owed(key)
    if (owed <= 0L) return
    val take = minOf(owed, cursor.amount.toLong()).toInt()
    if (take <= 0) return
    if (take >= cursor.amount) player.setItemOnCursor(null)
    else player.setItemOnCursor(cursor.clone().apply { amount -= take })
    moves.forgive(key, take.toLong())
}

/** Take what is still owed from the crafting grid the player has open. */
internal fun takeFromGrid(player: Player, moves: Moves) {
    if (moves.shortfalls <= 0L) return
    val grid = player.openGrid() ?: return
    val matrix = grid.matrix
    var changed = false
    for (i in matrix.indices) {
        val stack = matrix[i] ?: continue
        if (stack.isEmpty || stack.type.isAir) continue
        val key = stack.toItemKey()
        val owed = moves.owed(key)
        if (owed <= 0L) continue
        val take = minOf(owed, stack.amount.toLong()).toInt()
        matrix[i] = if (take >= stack.amount) null else stack.clone().apply { amount -= take }
        moves.forgive(key, take.toLong())
        changed = true
    }
    if (changed) grid.matrix = matrix
}

/**
 * Take remaining [Moves.owed] from the input slots of an open anvil, grindstone or other menu: an item parked
 * there is still the player's, and left alone it came back to their pockets on close as a copy.
 */
internal fun takeFromMenu(player: Player, moves: Moves) {
    if (moves.shortfalls <= 0L) return
    val menu = runCatching { player.openInventory.topInventory }.getOrNull() ?: return
    val slots = menu.transientInputSlots() ?: return
    for (i in slots) {
        if (i >= menu.size) break
        val stack = menu.getItem(i) ?: continue
        if (stack.isEmpty || stack.type.isAir) continue
        val key = stack.toItemKey()
        val owed = moves.owed(key)
        if (owed <= 0L) continue
        val take = minOf(owed, stack.amount.toLong()).toInt()
        menu.setItem(i, if (take >= stack.amount) null else stack.clone().apply { amount -= take })
        moves.forgive(key, take.toLong())
    }
}
