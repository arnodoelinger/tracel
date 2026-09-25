package com.tracel.plugin.rollback.material

import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.plan.RollbackPlan
import com.tracel.engine.rollback.plan.RollbackStep
import com.tracel.engine.rollback.plan.RollbackTarget
import com.tracel.engine.rollback.plan.destinationFor
import com.tracel.engine.wear.WearMark
import com.tracel.engine.wear.damageAt
import com.tracel.engine.wear.damageNow
import com.tracel.model.holder.HolderId
import com.tracel.model.id.LotId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.world.playerOf
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.matches
import io.papermc.paper.block.TileStateInventoryHolder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.ArmorStand
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.meta.Damageable

private data class Rewear(val lotId: LotId, val itemKey: ItemKey, val holder: HolderId, val current: Int?, val target: Int)
private data class WearEnd(val history: LotId, val lot: LotId, val holder: HolderId, val fresh: Boolean)

/** Every worn tool a rollback reached, back to the damage it had at [asOf]. */
internal suspend fun MaterialRestorer.rewearPlan(
    plan: RollbackPlan,
    target: RollbackTarget,
    job: RollbackJobId,
    asOf: Long,
    only: Set<HolderId>? = null,
) {
    val ends = LinkedHashMap<LotId, WearEnd>()
    val leaving = ArrayList<LotId>()
    services.atomically {
        for (step in plan.steps) {
            when (step) {
                is RollbackStep.Take -> ends[step.lotId] =
                    WearEnd(step.lotId, step.lotId, target.destinationFor(plan, step.lotId), fresh = ends[step.lotId]?.fresh == true)
                is RollbackStep.Mint -> services.ledger.compensationOf(step.lotId, job)?.let {
                    ends[it] = WearEnd(step.lotId, it, target.destinationFor(plan, step.lotId), fresh = true)
                }
                is RollbackStep.Debt -> services.ledger.compensationOf(step.lotId, job)?.let {
                    ends[it] = WearEnd(step.lotId, it, target.destinationFor(plan, step.lotId), fresh = true)
                }
                is RollbackStep.Unmake -> {
                    for ((lotId) in step.inputs) ends[lotId] = WearEnd(lotId, lotId, step.holder, fresh = true)
                    for ((lotId) in step.outputs) leaving += lotId
                }
            }
        }
    }
    val wanted = if (only == null) ends.values.toList() else ends.values.filter { it.holder in only }
    rewear(wanted, if (only == null) leaving else emptyList(), asOf)
}

/** The same after an undo: back to the damage each tool had when the job ran, [asOf]. */
internal suspend fun MaterialRestorer.rewearSteps(steps: List<InvolutionStep>, asOf: Long) {
    val ends = ArrayList<WearEnd>()
    val leaving = ArrayList<LotId>()
    for (step in steps) {
        when (step) {
            is InvolutionStep.Return -> ends += WearEnd(step.lotId, step.lotId, step.to, fresh = false)
            is InvolutionStep.Remake -> {
                for ((lotId, _, holder) in step.outputs) ends += WearEnd(lotId, lotId, holder, fresh = true)
                for ((lotId) in step.inputs) leaving += lotId
            }
            is InvolutionStep.Retract -> Unit
        }
    }
    rewear(ends, leaving, asOf)
}

private suspend fun MaterialRestorer.rewear(ends: List<WearEnd>, leaving: List<LotId>, asOf: Long) {
    if (ends.isEmpty()) return
    val (rewears, produced) = services.atomically {
        val keys = HashMap<LotId, ItemKey>()
        for (lot in ends.map { it.history } + leaving) {
            val itemKey = services.ledger.itemKeyOf(lot)
            if (WornStacks.wears(itemKey)) keys[lot] = itemKey
        }
        val marks = services.wear.marksOf(keys.keys)
        val produced = HashMap<ItemKey, MutableSet<Int>>()
        for ((lot, itemKey) in keys) {
            val damages = produced.getOrPut(itemKey) { hashSetOf(0) }
            marks[lot]?.damageNow?.let(damages::add)
        }
        val rewears = ends.mapNotNull { end ->
            val itemKey = keys[end.history] ?: return@mapNotNull null
            val history = marks[end.history] ?: return@mapNotNull null
            val damage = history.damageAt(asOf) ?: return@mapNotNull null
            Rewear(end.lot, itemKey, end.holder, if (end.fresh) null else history.damageNow, damage)
        }
        rewears to produced
    }

    val changing = rewears.groupBy { it.holder }.filterValues { group -> group.any { it.current != it.target } }
    if (changing.isEmpty()) return
    val done = coroutineScope {
        changing.map { (holder, wanted) -> async { rewearAt(holder, wanted, produced) } }.awaitAll().flatten()
    }
    if (done.isEmpty()) return
    val now = System.currentTimeMillis()
    services.atomically {
        for ((rewear, before) in done) services.wear.record(WearMark(rewear.lotId, now, before, rewear.target))
    }
}

private suspend fun MaterialRestorer.rewearAt(
    holder: HolderId,
    wanted: List<Rewear>,
    produced: Map<ItemKey, Set<Int>>,
): List<Pair<Rewear, Int>> = when (holder) {
    is HolderId.Player -> withContext(services.schedulers.entity(holder.uuid)) {
        playerOf(holder.uuid)?.let { rewearIn(it.inventory, wanted, produced) }.orEmpty()
    }
    is HolderId.EnderChest -> withContext(services.schedulers.entity(holder.uuid)) {
        playerOf(holder.uuid)?.let { rewearIn(it.enderChest, wanted, produced) }.orEmpty()
    }
    is HolderId.Block -> withContext(services.schedulers.region(holder)) {
        val state = worldOf(holder.world)?.getBlockAt(holder.x, holder.y, holder.z)?.getState(false)
        val inventory = (state as? TileStateInventoryHolder)?.inventory ?: (state as? InventoryHolder)?.inventory
        inventory?.let { rewearIn(it, wanted, produced) }.orEmpty()
    }
    is HolderId.Entity -> withContext(services.schedulers.entity(holder.uuid)) {
        when (val entity = Bukkit.getEntity(holder.uuid)) {
            is InventoryHolder -> rewearIn(entity.inventory, wanted, produced)
            is ArmorStand -> {
                val slots = EquipmentSlot.entries.filter { runCatching { entity.equipment.getItem(it) }.isSuccess }
                rewearSlots(
                    Array(slots.size) { entity.equipment.getItem(slots[it]) },
                    { i, stack -> entity.equipment.setItem(slots[i], stack) },
                    wanted, produced,
                )
            }
            is ItemFrame -> rewearSlots(arrayOf(entity.item), { _, stack -> entity.setItem(stack, false) }, wanted, produced)
            else -> emptyList()
        }
    }
    else -> emptyList()
}

private fun rewearIn(inventory: Inventory, wanted: List<Rewear>, produced: Map<ItemKey, Set<Int>>): List<Pair<Rewear, Int>> =
    rewearSlots(inventory.contents, inventory::setItem, wanted, produced)

private fun rewearSlots(
    contents: Array<ItemStack?>,
    write: (Int, ItemStack) -> Unit,
    wanted: List<Rewear>,
    produced: Map<ItemKey, Set<Int>>,
): List<Pair<Rewear, Int>> {
    val used = BooleanArray(contents.size)
    val done = ArrayList<Pair<Rewear, Int>>(wanted.size)

    fun claim(rewear: Rewear, exact: Boolean): Boolean {
        for (slot in contents.indices) {
            if (used[slot]) continue
            val stack = contents[slot] ?: continue
            if (stack.isEmpty || !stack.matches(rewear.itemKey)) continue
            val meta = stack.itemMeta as? Damageable ?: continue
            val before = meta.damage
            if (exact && before != rewear.current) continue
            if (!exact && before !in produced[rewear.itemKey].orEmpty()) continue
            used[slot] = true
            if (before != rewear.target) {
                meta.damage = rewear.target
                stack.itemMeta = meta
                write(slot, stack)
                done += rewear to before
            }
            return true
        }
        return false
    }

    val loose = wanted.filter { it.current == null || !claim(it, exact = true) }
    val owed = loose.groupingBy { it.itemKey }.eachCount()
    for (rewear in loose) {
        val candidates = contents.indices.count { slot ->
            val stack = contents[slot]
            !used[slot] && stack != null && !stack.isEmpty && stack.matches(rewear.itemKey) &&
                (stack.itemMeta as? Damageable)?.damage in produced[rewear.itemKey].orEmpty()
        }
        if (candidates > (owed[rewear.itemKey] ?: 0)) continue
        claim(rewear, exact = false)
    }
    return done
}
