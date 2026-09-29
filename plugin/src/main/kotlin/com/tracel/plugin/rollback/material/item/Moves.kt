package com.tracel.plugin.rollback.material.item

import com.tracel.annotations.Unstable
import com.tracel.model.item.ItemKey
import com.tracel.plugin.rollback.material.ApplyResult
import org.bukkit.inventory.ItemStack

/** Overflow: ledger already credited, must spill. Shortfall: world still has it, name it. */
@Unstable
internal class Moves {
    val overflow = mutableListOf<Pair<ItemKey, ItemStack>>()
    private val shortfall = LinkedHashMap<ItemKey, Long>()
    private val problems = mutableListOf<String>()

    fun short(itemKey: ItemKey, amount: Long) {
        if (amount > 0L) shortfall.merge(itemKey, amount, Long::plus)
    }

    fun problem(text: String) {
        problems += text
    }

    fun forgive(itemKey: ItemKey, amount: Long) {
        val left = (shortfall[itemKey] ?: return) - amount
        if (left > 0L) shortfall[itemKey] = left else shortfall.remove(itemKey)
    }

    fun owed(itemKey: ItemKey): Long = shortfall[itemKey] ?: 0L

    val shortfalls: Long get() = shortfall.values.sum()

    /** [reason] as a failed apply that names exactly what could not be taken. */
    fun failure(): ApplyResult.Failed? = reason?.let { ApplyResult.Failed(it, LinkedHashMap(shortfall)) }

    val reason: String?
        get() {
            val all = problems + shortfall.map { (key, amount) -> "${key.material} x$amount could not be removed" }
            return all.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }
}
