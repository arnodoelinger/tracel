package com.tracel.plugin.rollback.material.holder

import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.adapter.entity.cargoStacks
import com.tracel.plugin.adapter.entity.resyncCargoViewers
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.rollback.material.ApplyResult
import com.tracel.plugin.rollback.material.ENTITY_GONE_AFTER_RESTORE
import com.tracel.plugin.rollback.material.ENTITY_GONE_AT_PLAN
import com.tracel.plugin.rollback.material.MaterialRestorer
import com.tracel.plugin.rollback.material.cargo.applyArmorStand
import com.tracel.plugin.rollback.material.cargo.applyItemFrame
import com.tracel.plugin.rollback.material.census.EntityCensus
import com.tracel.plugin.rollback.material.census.census
import com.tracel.plugin.rollback.material.item.Moves
import com.tracel.plugin.rollback.material.item.WornStacks
import com.tracel.plugin.rollback.material.item.applyDelta
import com.tracel.plugin.rollback.material.item.stackFor
import com.tracel.plugin.rollback.material.item.stacksOf
import com.tracel.plugin.rollback.material.spill.Spill
import com.tracel.plugin.rollback.material.spill.spillInRegion
import com.tracel.plugin.util.regionKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.Location
import org.bukkit.World
import kotlinx.coroutines.future.await
import com.tracel.plugin.adapter.world.worldOf
import com.tracel.plugin.rollback.material.item.matches
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.entity.Mob
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.ChestedHorse
import org.bukkit.entity.ItemFrame
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

private val CHEST: ItemKey = ItemStack(Material.CHEST).toItemKey()

private const val CHEST_STORAGE_FROM = 2

private const val NO_HULL = "entity no longer exists"
private const val ELSEWHERE = "entity is in another region"

/** One global lookup for entity cargo restore. */
internal suspend fun MaterialRestorer.restoreEntityCargo(
    work: Map<HolderId.Entity, Map<ItemKey, Long>>,
    forms: Map<ItemKey, ByteArray>,
    gone: Set<HolderId>,
    census: EntityCensus,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): List<Pair<HolderId, ApplyResult>> {
    val out = ArrayList<Pair<HolderId, ApplyResult>>(work.size)
    val live = LinkedHashMap<HolderId.Entity, Map<ItemKey, Long>>()
    for ((holder, deltas) in work) {
        val taking = deltas.values.all { it < 0L }
        if (holder in gone && taking) out += holder to ENTITY_GONE_AT_PLAN
        else live[holder] = deltas
    }
    if (live.isEmpty()) return out

    data class Located(
        val holder: HolderId.Entity,
        val deltas: Map<ItemKey, Long>,
        val at: HolderId.Block,
        val needsTracker: Boolean,
    )

    val found = ArrayList<Located>(live.size)
    val missing = ArrayList<HolderId.Entity>()
    val unknown = LinkedHashMap<HolderId.Entity, Map<ItemKey, Long>>()
    for ((holder, deltas) in live) {
        val where = census.at[holder.uuid]
        when {
            where != null -> found += Located(holder, deltas, where, holder.uuid in census.trackerNeeded)
            holder.uuid in census.missing -> missing += holder
            else -> unknown[holder] = deltas
        }
    }
    // Census miss: one extra trip, not one per holder
    if (unknown.isNotEmpty()) {
        val late = withContext(services.schedulers.global) { census(HashSet<HolderId>(unknown.keys), unknown) }
        for ((holder, deltas) in unknown) {
            val where = late.at[holder.uuid]
            if (where == null) missing += holder
            else found += Located(holder, deltas, where, holder.uuid in late.trackerNeeded)
        }
    }
    val located = Pair(found, missing)
    for (holder in located.second) out += holder to ENTITY_GONE_AFTER_RESTORE
    val waitFor = located.first.firstOrNull { it.needsTracker }
    if (waitFor != null) awaitTicks(2)

    val grouped = located.first.groupBy { it.at.regionKey() }
    val applied = coroutineScope {
        grouped.values.map { group ->
            async {
                val at = group.first().at
                worldOf(at.world)?.let { world -> runCatching { world.getChunkAtAsync(at.x shr 4, at.z shr 4).await() } }
                withContext(services.schedulers.region(at)) {
                    val world = worldOf(at.world)
                    if (world != null) runCatching { world.getChunkAt(at.x shr 4, at.z shr 4).entities }
                    group.map { row ->
                        row.holder to inRegion {
                            val failed = fillEntityCargo(row.holder, row.deltas, forms, sink, asOf, worn)
                            if (failed == NO_HULL && world != null) spillGives(row.holder, row.deltas, forms, world, row.at, sink)
                            failed
                        }
                    }
                }.map { (holder, result) ->
                    if ((result as? ApplyResult.Failed)?.reason != ELSEWHERE) return@map holder to result
                    val row = group.first { it.holder == holder }
                    holder to withContext(services.schedulers.entity(holder.uuid)) {
                        inRegion { fillEntityCargo(row.holder, row.deltas, forms, sink, asOf, worn) }
                    }
                }
            }
        }.awaitAll().flatten()
    }
    out += applied
    return out
}

/**
 * Fill entity cargo.
 *
 * Must run on the entity's region. Frames / stands are one-slot; boats / mules are inventories.
 */
internal suspend fun MaterialRestorer.fillEntityCargo(
    holder: HolderId.Entity,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    sink: MutableCollection<Spill>,
    asOf: Long? = null,
    worn: WornStacks? = null,
): String? {
    val entity = Bukkit.getEntity(holder.uuid) ?: return NO_HULL
    if (!Bukkit.isOwnedByCurrentRegion(entity)) return ELSEWHERE
    val moves = Moves()
    when (entity) {
        is ArmorStand -> {
            applyArmorStand(entity, deltas, forms, moves)
            entity.resyncCargoViewers(services.plugin)
            services.differ.forget(holder)
        }
        is ItemFrame -> {
            applyItemFrame(entity, deltas, forms, moves)
            entity.resyncCargoViewers(services.plugin)
            services.differ.forget(holder)
        }
        is InventoryHolder -> {
            val inventory = entity.inventory
            val horse = entity as? ChestedHorse
            val chestDelta = deltas[CHEST] ?: 0L

            // Worn chest is a flag
            val stored = inventory.contents.sumOf { stack ->
                if (stack != null && stack.toItemKey() == CHEST) stack.amount.toLong() else 0L
            }
            val attach = horse != null && !horse.isCarryingChest && chestDelta > 0L
            val detach = horse != null && horse.isCarryingChest && chestDelta < 0L && stored < -chestDelta

            if (attach) horse.isCarryingChest = true
            // After attach, getInventory() is a new wrapper
            val live = if (attach) entity.inventory else inventory
            val preferredSlots = asOf?.let { services.containerSlots.layoutAt(holder, it) }
                ?.groupBy { it.itemKey }
                .orEmpty()

            for ((itemKey, delta) in deltas) {
                // Worn chest is the flag
                val amount = when {
                    itemKey != CHEST -> delta
                    attach -> delta - 1L
                    detach -> delta + 1L
                    else -> delta
                }
                if (amount != 0L) {
                    applyDelta(itemKey, amount, forms[itemKey], moves, live, preferredSlots[itemKey].orEmpty(), worn)
                }
            }

            // Detach last: taking the chest off removes the slots, so whatever still sits in them goes on
            // the ground first, as vanilla drops it, instead of vanishing with them.
            if (detach) {
                for (slot in CHEST_STORAGE_FROM until live.size) {
                    val left = live.getItem(slot) ?: continue
                    if (left.isEmpty || left.type.isAir) continue
                    moves.overflow += left.toItemKey() to left.clone()
                    live.setItem(slot, null)
                }
                horse.isCarryingChest = false
            }

            // Chest flag is metadata the client often misses
            if (attach || detach) entity.resyncCargoViewers(services.plugin)

            services.differ.rebaseline(holder, entity.cargoStacks().toItemTotals())
        }
        // What a mob holds or wears it picked up or was given: take it out of the slot, give it back into one
        is Mob -> {
            applyMobEquipment(entity, deltas, forms, moves)
            services.differ.forget(holder)
        }
        // Mint-through mob (egg via chicken): no inventory. Spill under the animal rather than vanish
        else -> {
            for ((itemKey, delta) in deltas) {
                if (delta <= 0L) {
                    moves.short(itemKey, -delta)
                    continue
                }
                val template = stackFor(itemKey, 1, forms[itemKey])
                if (template == null) {
                    moves.problem("${itemKey.material} is not an item this server can build")
                    continue
                }
                for (over in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to over
            }
        }
    }
    spillInRegion(holder, moves, entity.world, entity.location, sink)
    return moves.reason
}

private fun MaterialRestorer.applyMobEquipment(mob: Mob, deltas: Map<ItemKey, Long>, forms: Map<ItemKey, ByteArray>, moves: Moves) {
    val equipment = mob.equipment
    for ((itemKey, delta) in deltas.entries.sortedBy { it.value > 0L }) {
        if (delta < 0L) {
            var left = -delta
            for (slot in EquipmentSlot.entries) {
                if (left <= 0L) break
                val held = runCatching { equipment.getItem(slot) }.getOrNull() ?: continue
                if (held.isEmpty || !held.matches(itemKey)) continue
                val take = minOf(left, held.amount.toLong())
                equipment.setItem(slot, if (take >= held.amount) ItemStack.empty() else held.clone().apply { amount -= take.toInt() })
                left -= take
            }
            moves.short(itemKey, left)
            continue
        }
        val template = stackFor(itemKey, 1, forms[itemKey])
        if (template == null) {
            moves.problem("${itemKey.material} is not an item this server can build")
            continue
        }
        var left = delta
        val natural = runCatching { template.type.equipmentSlot }.getOrNull()
        for (slot in listOfNotNull(natural, EquipmentSlot.HAND).distinct()) {
            if (left <= 0L) break
            if (!runCatching { mob.canUseEquipmentSlot(slot) }.getOrDefault(false)) continue
            val held = runCatching { equipment.getItem(slot) }.getOrNull()
            if (held != null && !held.isEmpty) continue
            val give = if (slot == EquipmentSlot.HAND) minOf(left, template.maxStackSize.toLong()) else 1L
            equipment.setItem(slot, template.clone().apply { amount = give.toInt() })
            runCatching { equipment.setDropChance(slot, 1f) }
            left -= give
        }
        for (over in stacksOf(itemKey, left, template)) moves.overflow += itemKey to over
    }
}

private fun MaterialRestorer.spillGives(
    holder: HolderId.Entity,
    deltas: Map<ItemKey, Long>,
    forms: Map<ItemKey, ByteArray>,
    world: World,
    at: HolderId.Block,
    sink: MutableCollection<Spill>,
) {
    val moves = Moves()
    for ((itemKey, delta) in deltas) {
        if (delta <= 0L) continue
        val template = stackFor(itemKey, 1, forms[itemKey]) ?: continue
        for (stack in stacksOf(itemKey, delta, template)) moves.overflow += itemKey to stack
    }
    if (moves.overflow.isNotEmpty()) spillInRegion(holder, moves, world, Location(world, at.x + 0.5, at.y + 1.0, at.z + 0.5), sink)
}
