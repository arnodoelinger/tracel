package com.tracel.plugin.listener.support.drop

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.adapter.item.toItemStacks
import com.tracel.plugin.listener.support.flow.releaseFlows
import com.tracel.plugin.listener.support.flow.worldgenMintFlows
import com.tracel.plugin.util.Warnings
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.resume

fun TracelServices.spawnAsRelease(
    itemKey: ItemKey,
    quantity: Long,
    world: World,
    location: Location
): List<InventoryDelta> =
    itemKey.toItemStacks(quantity).map { stack -> dropTracked(stack, itemKey, world, location) }

internal fun TracelServices.dropTracked(
    stack: ItemStack,
    itemKey: ItemKey,
    world: World,
    location: Location,
): InventoryDelta {
    val item = selfManagedSpawns.whileSpawning { world.dropItemNaturally(location, stack) }

    // Track until pickup / rollback: blast merge swaps UUID, ledger names the dead one, diamonds stay
    selfManagedSpawns.track(item.uniqueId)
    // the vanished check asks from the global thread, blind to this region: without a hint the pile read as gone
    groundWhereabouts.remember(item)
    return InventoryDelta(HolderId.ItemEntity(item.uniqueId), itemKey, stack.amount.toLong())
}

@Suppress("IfThenToElvis")
fun TracelServices.releaseAsTrackedDrops(
    holder: HolderId,
    at: Location,
    cause: CauseKind,
    causedBy: HolderId?,
    epochMillis: Long,
    removed: List<ItemStack>? = null,
    andThen: (suspend () -> Unit)? = null,
) {
    val worldId = at.world.uid
    val where = BlockPos(WorldId(worldId), at.blockX, at.blockY, at.blockZ)
    val region = HolderId.Block(WorldId(worldId), at.blockX, at.blockY, at.blockZ)
    val physical = removed?.filter { !it.type.isAir && it.amount > 0 }

    // Flush / owed: two region ticks plus storage sit between return and write; untracked flush skips them
    val ticket = pendingCaptures.owed()
    scope.launch {
        try {
            val believed = atomically { ledger.totalsAt(holder) }.mapValues { it.value.raw }

            // Physical truth hits the floor; ledger only sizes the mint vs move (releaseFlows)
            val keyed: List<Pair<ItemStack, ItemKey>>? =
                physical?.map { stack -> stack to stack.toItemKey() }
            val release: Map<ItemKey, Long> = if (keyed == null) believed else {
                val totals = mutableMapOf<ItemKey, Long>()
                for ((stack, itemKey) in keyed) totals[itemKey] = (totals[itemKey] ?: 0L) + stack.amount
                totals
            }
            if (release.isNotEmpty()) {
                val spawned: List<InventoryDelta> = withContext(schedulers.region(region)) {
                    val world = Bukkit.getWorld(worldId)
                    if (world == null) {
                        releaseLogger.warning("world for $holder unloaded before its contents could be respawned — contents lost")
                        return@withContext emptyList()
                    }
                    // Folia: wait out the blast tick or spawned UUIDs die and rollback compensates
                    awaitRegionTicks(region, 2)
                    if (keyed != null) keyed.map { (stack, itemKey) -> dropTracked(stack, itemKey, world, at) }
                    else believed.flatMap { (itemKey, qty) -> spawnAsRelease(itemKey, qty, world, at) }
                }
                val flows = releaseFlows(holder, believed, release, spawned)
                val (mints, rest) = flows.partition { it.kind == FlowKind.MINT }
                atomically {
                    if (mints.isNotEmpty()) capture.recordDirect(mints, epochMillis - 1, CauseKind.WORLD, null, where)
                    if (rest.isNotEmpty()) capture.recordDirect(rest, epochMillis, cause, causedBy, where)
                }
            }
        } catch (e: IllegalStateException) {
            releaseLogger.log(Level.FINE, "untracked material in $holder, not recorded", e)
        }
        differ.forget(holder)
        andThen?.invoke()
    }.invokeOnCompletion { pendingCaptures.done(ticket) }
}

private val releaseLogger = Logger.getLogger("SelfManagedDrop")

private suspend fun TracelServices.awaitRegionTicks(region: HolderId.Block, ticks: Long) {
    if (ticks <= 0L) return
    val world = Bukkit.getWorld(region.world.uuid) ?: return
    val loc = Location(world, region.x.toDouble(), region.y.toDouble(), region.z.toDouble())
    suspendCancellableCoroutine { cont ->
        Bukkit.getRegionScheduler().runDelayed(plugin, loc, {
            if (cont.isActive) cont.resume(Unit)
        }, ticks)
    }
}

fun TracelServices.recordAt(
    cause: CauseKind,
    causedBy: HolderId?,
    epochMillis: Long,
    at: Location,
    deltas: List<InventoryDelta>,
    mintShortfallAt: HolderId? = null,
) {
    if (deltas.isEmpty()) return
    val where = BlockPos(WorldId(at.world.uid), at.blockX, at.blockY, at.blockZ)
    // Owed before launch: click-then-rollback flush used to skip this write
    val ticket = pendingCaptures.owed()
    scope.launch {
        try {
            atomically {
                if (mintShortfallAt != null) mintUnseen(mintShortfallAt, deltas, epochMillis, cause, causedBy, where)
                capture.record(deltas, epochMillis, cause, causedBy, where)
            }
        } catch (e: IllegalStateException) {
            Warnings.once(releaseLogger, "untracked:$cause:${causedBy ?: where}") {
                "material in $cause at $where is not on the ledger's books, so it was not recorded " +
                        "— a rollback cannot put back what was never written down (${e.message})"
            }
        }
    }.invokeOnCompletion { pendingCaptures.done(ticket) }
}

private suspend fun TracelServices.mintUnseen(
    holder: HolderId,
    deltas: List<InventoryDelta>,
    epochMillis: Long,
    cause: CauseKind,
    causedBy: HolderId?,
    where: BlockPos,
) {
    val leaving = LinkedHashMap<ItemKey, Long>()
    for ((holder1, itemKey, delta1) in deltas) {
        if (holder1 != holder || delta1 >= 0L) continue
        leaving.merge(itemKey, -delta1, Long::plus)
    }
    if (leaving.isEmpty()) return

    val believed = ledger.totalsAt(holder).mapValues { it.value.raw }
    val shortfall = LinkedHashMap<ItemKey, Long>()
    for ((itemKey, amount) in leaving) {
        val missing = amount - (believed[itemKey] ?: 0L)
        if (missing > 0L) shortfall[itemKey] = missing
    }
    if (shortfall.isEmpty()) return

    for ((itemKey, amount) in shortfall) differ.adjust(holder, itemKey, amount)
    capture.recordDirect(worldgenMintFlows(shortfall, holder), epochMillis - 1, CauseKind.WORLD, null, where)
}
