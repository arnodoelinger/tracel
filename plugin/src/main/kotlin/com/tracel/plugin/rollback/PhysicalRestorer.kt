package com.tracel.plugin.rollback

import com.tracel.engine.rollback.RollbackPlan
import com.tracel.engine.rollback.involution.InvolutionStep
import com.tracel.engine.rollback.physicalDeltas
import com.tracel.engine.rollback.physicalDeltasForUndo
import com.tracel.model.holder.HolderId
import com.tracel.model.id.RollbackJobId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toItemTotals
import com.tracel.plugin.convert.withCursor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs

/** What [PhysicalRestorer.restore] found before it was ever allowed to touch the ledger. */
sealed interface PreflightResult {
    data object Ok : PreflightResult
    data class Unreachable(val holder: HolderId, val reason: String) : PreflightResult
}

/**
 * What actually happened once the ledger mutation succeeded and physical restoration ran.
 * [queued] is not a failure — it lists holders (always offline players) whose material was
 * durably queued for delivery instead, and will still arrive, whenever they next join.
 */
data class RestorationReport(
    val failures: Map<HolderId, String>,
    val queued: Map<HolderId, String> = emptyMap(),
) {
    val fullyRestored: Boolean get() = failures.isEmpty()
}

private sealed interface ApplyResult {
    data object Ok : ApplyResult
    data class Failed(val reason: String) : ApplyResult
    data class Queued(val note: String) : ApplyResult
}

/**
 * The bridge `RollbackJobCoordinator` never had: applying a rollback only ever moved lots in the
 * ledger, never touched a real `Bukkit` inventory. Found live the moment `/tracel rollback apply`
 * was first tried — `/tracel audit` immediately showed fresh drift because nothing physically moved.
 *
 * Two-phase by necessity: [preflight] checks every holder [physicalDeltas] touches is reachable
 * before the ledger mutates, since `Bukkit`'s inventory APIs are synchronous and non-transactional —
 * there's no way to "prepare" a real mutation the way the ledger's own escrow does. [restore] is
 * only ever called after a `RollbackOutcome.Applied`.
 *
 * A [HolderId.Player] is never actually unreachable: offline, [restore] durably queues their
 * material via [TracelServices.pendingDeliveries] instead of failing.
 */
class PhysicalRestorer(private val services: TracelServices) {
    private val logger = Logger.getLogger(PhysicalRestorer::class.java.name)

    /** Checks every holder [plan] (restored to [restoreTo]) touches is physically reachable right now. */
    suspend fun preflight(plan: RollbackPlan, restoreTo: HolderId): PreflightResult =
        preflight(physicalDeltas(plan, restoreTo, services.ledger))

    /** Checks every holder undoing [steps] touches is physically reachable right now. */
    suspend fun undoPreflight(steps: List<InvolutionStep>): PreflightResult =
        preflight(physicalDeltasForUndo(steps))

    /**
     * Checks every holder [deltas] touches is physically reachable right now.
     *
     * Every holder is checked at once rather than one after another. Each check is a hop onto
     * whichever region or entity thread owns that holder, those threads tick genuinely in parallel
     * under `Folia`, and a rollback spanning fifty containers has no reason to visit them in single
     * file. The answer still reports the first unreachable holder in [deltas] order, so what the
     * admin sees does not depend on which region happened to answer first.
     */
    private suspend fun preflight(deltas: Map<HolderId, Map<ItemKey, Long>>): PreflightResult = coroutineScope {
        val touched = deltas.filterValues { itemDeltas -> itemDeltas.values.any { it != 0L } }.keys.toList()
        val reasons = touched.map { holder -> async { holder to unreachableReason(holder) } }.awaitAll()
        reasons.firstNotNullOfOrNull { (holder, reason) -> reason?.let { PreflightResult.Unreachable(holder, it) } }
            ?: PreflightResult.Ok
    }

    /** Returns a reason [holder] is unreachable, or null if reachable. */
    private suspend fun unreachableReason(holder: HolderId): String? = when (holder) {
        // Never unreachable, see the class doc. Online, restore() touches their real inventory
        // directly; offline, it queues the delta durably instead of failing.
        is HolderId.Player -> null

        is HolderId.Block -> withContext(services.schedulers.region(holder)) {
            val world = Bukkit.getWorld(holder.world.uuid)
            when {
                world == null -> "world is not loaded"
                world.getBlockAt(holder.x, holder.y, holder.z).getState(false) !is Container -> "block is not a container"
                else -> null
            }
        }

        is HolderId.ItemEntity -> withContext(services.schedulers.entity(holder.uuid)) {
            if (Bukkit.getEntity(holder.uuid) !is Item) "ground item no longer exists" else null
        }

        else -> "holder type $holder cannot be physically restored"
    }

    /** Physically applies [plan] (restored to [restoreTo]) — call only after the ledger already applied it. */
    suspend fun restore(plan: RollbackPlan, restoreTo: HolderId, job: RollbackJobId): RestorationReport =
        restore(physicalDeltas(plan, restoreTo, services.ledger), job)

    /** Physically applies the undo of [steps] — call only after the ledger already undid it. */
    suspend fun undoRestore(steps: List<InvolutionStep>, job: RollbackJobId): RestorationReport =
        restore(physicalDeltasForUndo(steps), job)

    /**
     * Physically applies [deltas] — call only after the ledger already applied it.
     *
     * Fanned out per holder like [preflight], and for the same reason: the ledger has already
     * decided what each holder owes, no holder's outcome depends on any other's, and doing them one
     * region round trip at a time is how a large rollback turned into a visible stall. The report is
     * still assembled in [deltas] order.
     */
    private suspend fun restore(deltas: Map<HolderId, Map<ItemKey, Long>>, job: RollbackJobId): RestorationReport =
        coroutineScope {
            val outcomes = deltas
                .mapValues { (_, itemDeltas) -> itemDeltas.filterValues { it != 0L } }
                .filterValues { it.isNotEmpty() }
                .map { (holder, nonZero) -> async { holder to applyTo(holder, nonZero, job) } }
                .awaitAll()

            val failures = mutableMapOf<HolderId, String>()
            val queued = mutableMapOf<HolderId, String>()
            for ((holder, result) in outcomes) {
                when (result) {
                    is ApplyResult.Failed -> {
                        logger.log(Level.WARNING, "physical restoration incomplete for $holder: ${result.reason}")
                        failures[holder] = result.reason
                    }
                    is ApplyResult.Queued -> queued[holder] = result.note
                    ApplyResult.Ok -> {}
                }
            }

            RestorationReport(failures, queued)
        }

    private suspend fun applyTo(holder: HolderId, deltas: Map<ItemKey, Long>, job: RollbackJobId): ApplyResult = when (holder) {
        is HolderId.Player -> applyToPlayer(holder, deltas, job)
        is HolderId.Block -> applyToContainer(holder, deltas)?.let(ApplyResult::Failed) ?: ApplyResult.Ok
        is HolderId.ItemEntity -> applyToItemEntity(holder, deltas)?.let(ApplyResult::Failed) ?: ApplyResult.Ok
        else -> ApplyResult.Failed("holder type not physically restorable: $holder")
    }

    /** Applies [deltas] to [holder] if online, or queues them for delivery if offline. */
    private suspend fun applyToPlayer(holder: HolderId.Player, deltas: Map<ItemKey, Long>, job: RollbackJobId): ApplyResult =
        withContext(services.schedulers.entity(holder.uuid)) {
            val player = Bukkit.getPlayer(holder.uuid)
            if (player == null) {
                services.atomically {
                    services.pendingDeliveries.enqueueAll(holder.uuid, deltas, job, System.currentTimeMillis())
                }
                return@withContext ApplyResult.Queued("player is offline - ${deltas.size} item key(s) queued for delivery on next login")
            }
            val leftovers = mutableListOf<String>()
            for ((itemKey, delta) in deltas) applyDelta(itemKey, delta, leftovers, add = { player.inventory.addItem(it) }, remove = { player.inventory.removeItemAnySlot(it) })
            services.differ.rebaseline(holder, player.inventory.toItemTotals().withCursor(player))
            leftovers.takeIf { it.isNotEmpty() }?.let { ApplyResult.Failed(it.joinToString("; ")) } ?: ApplyResult.Ok
        }

    /**
     * Delivers whatever [TracelServices.pendingDeliveries] owes [player] — call the moment they
     * join. Claims (reads and deletes, atomically) before ever touching the real inventory.
     */
    suspend fun deliverPending(player: Player): RestorationReport {
        val claimed = services.atomically { services.pendingDeliveries.claimFor(player.uniqueId) }
        if (claimed.isEmpty()) return RestorationReport(emptyMap())

        return withContext(services.schedulers.entity(player.uniqueId)) {
            val leftovers = mutableListOf<String>()
            for ((_, itemKey, delta) in claimed) {
                applyDelta(itemKey, delta, leftovers, add = { player.inventory.addItem(it) }, remove = { player.inventory.removeItemAnySlot(it) })
            }
            services.differ.rebaseline(HolderId.Player(player.uniqueId), player.inventory.toItemTotals().withCursor(player))

            val holder = HolderId.Player(player.uniqueId)
            if (leftovers.isEmpty()) {
                RestorationReport(emptyMap())
            } else {
                logger.log(Level.WARNING, "delivering queued material to $holder was incomplete: ${leftovers.joinToString("; ")}")
                RestorationReport(mapOf(holder to leftovers.joinToString("; ")))
            }
        }
    }

    private suspend fun applyToContainer(holder: HolderId.Block, deltas: Map<ItemKey, Long>): String? =
        withContext(services.schedulers.region(holder)) {
            val world = Bukkit.getWorld(holder.world.uuid) ?: return@withContext "world is not loaded"
            // A live state operates on the real block's data directly, mutating its
            // inventory takes effect immediately, no separate update() round-trip needed.
            val state = world.getBlockAt(holder.x, holder.y, holder.z).getState(false)
            if (state !is Container) return@withContext "block is no longer a container"

            val leftovers = mutableListOf<String>()
            for ((itemKey, delta) in deltas) applyDelta(itemKey, delta, leftovers, add = { state.inventory.addItem(it) }, remove = { state.inventory.removeItem(it) })
            services.differ.rebaseline(holder, state.inventory.toItemTotals())
            leftovers.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

    private suspend fun applyToItemEntity(holder: HolderId.ItemEntity, deltas: Map<ItemKey, Long>): String? =
        withContext(services.schedulers.entity(holder.uuid)) {
            val item = Bukkit.getEntity(holder.uuid) as? Item ?: return@withContext "ground item no longer exists"
            val entry = deltas.entries.singleOrNull()
                ?: return@withContext "a ground item can only ever be a single-item-key Take source, got ${deltas.keys}"
            val (itemKey, delta) = entry
            if (delta >= 0) return@withContext "a ground item can only ever lose material during a rollback, got a gain"
            if (Material.valueOf(itemKey.material) != item.itemStack.type) return@withContext "ground item is no longer ${itemKey.material}"

            val remaining = item.itemStack.amount + delta
            if (remaining <= 0) item.remove() else item.itemStack = item.itemStack.apply { amount = remaining.toInt() }
            null
        }

    /**
     * Adds [itemKey] x|delta| via [add] if positive, via [remove] if negative — [leftovers] collects what didn't
     * fit / wasn't found.
     */
    private fun applyDelta(
        itemKey: ItemKey,
        delta: Long,
        leftovers: MutableList<String>,
        add: (ItemStack) -> Map<Int, ItemStack>,
        remove: (ItemStack) -> Map<Int, ItemStack>,
    ) {
        if (itemKey.decoration != null) {
            logger.warning("cannot physically reconstruct decorated item $itemKey — restoring a plain ${itemKey.material} stack instead")
        }
        val material = runCatching { Material.valueOf(itemKey.material) }.getOrNull()
        if (material == null) {
            leftovers += "unknown material ${itemKey.material}"
            return
        }
        val stack = ItemStack(material, abs(delta).toInt())
        val leftover = if (delta > 0) add(stack) else remove(stack)
        if (leftover.isNotEmpty()) {
            val verb = if (delta > 0) "didn't fit" else "could not be removed"
            leftovers += "${itemKey.material} x${leftover.values.sumOf { it.amount }} $verb"
        }
    }
}
