package com.tracel.plugin.listener.explosion

import com.tracel.annotations.CauseKind
import com.tracel.engine.balance.InventoryDelta
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.TracelServices
import com.tracel.plugin.convert.toHolderId
import com.tracel.plugin.convert.toPlacedBlockId
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Creeper
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

/**
 * Releases everything an explosion destroys. One combined transaction per explosion.
 */
class ExplosionCaptureListener(private val services: TracelServices) : Listener {
    private val logger = Logger.getLogger(ExplosionCaptureListener::class.java.name)

    /** Entity explosions can be attributed to someone. Block explosions can't. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val causedBy = causedByOf(event.entity)
        // Record even if this blast hit nothing tracked — a chained TNT a moment later
        // will need this to know who's responsible, and won't know any other way.
        if (causedBy != null) services.redstoneTriggers.recordExplosion(event.location, causedBy)
        capture(event.blockList(), causedBy)
    }

    /** Block explosions (beds, respawn anchors, etc.) are unattributed and just release whatever they touch. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) = capture(event.blockList(), causedBy = null)

    /** Resolves who's responsible for an ignition, recursing through chains like creeper -> TNT. */
    private fun causedByOf(entity: Entity, depth: Int = 0): HolderId? {
        if (depth >= MAX_IGNITION_CHAIN_DEPTH) return null
        return when (entity) {
            is TNTPrimed -> igniterOf(entity, depth)
            // A creeper that just wandered close to a player fires no event and gets null here
            // for free — only one lit with flint and steel is in the tracker at all.
            is Creeper -> services.redstoneTriggers.creeperIgnitedBy(entity.uniqueId)
            else -> null
        }
    }

    /**
     * Walks `TNTPrimed.source` back to whoever's actually responsible. `Bukkit` sets it to the
     * lighting player or the previous TNT in a chain; leaves it null for redstone and for TNT
     * caught in someone else's blast, so those two get resolved by hand against
     * [com.tracel.plugin.listener.redstone.RedstoneTriggerTracker] instead.
     */
    private fun igniterOf(tnt: TNTPrimed, depth: Int = 0): HolderId? {
        if (depth >= MAX_IGNITION_CHAIN_DEPTH) return null
        return when (val source = tnt.source) {
            null -> services.redstoneTriggers.recentPressNear(tnt.world, tnt.location.blockX, tnt.location.blockY, tnt.location.blockZ)
                ?: services.redstoneTriggers.recentExplosionNear(tnt.location)
            is Player -> HolderId.Player(source.uniqueId)
            else -> causedByOf(source, depth + 1)
        }
    }

    private fun capture(blocks: List<Block>, causedBy: HolderId?) {
        if (blocks.isEmpty()) return

        val containerHolders = blocks.filter { it.state is Container }.map { it.toHolderId() }
        val releaseTargets: List<Pair<HolderId, Block>> =
            blocks.filter { it.state is Container }.map { it.toHolderId() as HolderId to it } +
            blocks.map { it.toPlacedBlockId() as HolderId to it }
        val epochMillis = System.currentTimeMillis()

        services.scope.launch {
            // Tracks which holders actually got registered, so a throw mid-loop doesn't leak them
            val resolved = mutableListOf<HolderId>()
            try {
                val believedByHolder = services.atomically {
                    releaseTargets.associate { (holder, block) ->
                        val believed: Map<ItemKey, Long> = services.ledger.totalsAt(holder).mapValues { it.value.raw }
                        services.explosionDrops.resolve(holder, block.world, block.x, block.y, block.z, believed)
                        resolved += holder
                        holder to believed
                    }
                }

                // Gives ItemEntityCaptureListener's own deferred claim check (a handful of ticks,
                // triggered independently by each real spawn) time to run before this decides
                // what's left unclaimed.
                delay(CLAIM_WINDOW_MILLIS.milliseconds)

                val deltas = believedByHolder.flatMap { (holder, believed) ->
                    val result = services.explosionDrops.finish(holder)
                    believed.map { (itemKey, qty) -> InventoryDelta(holder, itemKey, -qty) } +
                        result.claimed.map { InventoryDelta(it.entity, it.itemKey, it.quantity) }
                }
                if (deltas.isNotEmpty()) {
                    services.atomically {
                        services.capture.record(deltas, epochMillis, CauseKind.EXPLOSION, causedBy)
                    }
                }
                containerHolders.forEach { services.differ.forget(it) }
            } catch (e: IllegalStateException) {
                logger.log(Level.FINE, "explosion touched untracked material, not fully recorded", e)
            } finally {
                // No-op for anything the loop above already finished; the point is the paths that
                // did not reach it.
                resolved.forEach { services.explosionDrops.finish(it) }
            }
        }
    }

    private companion object {
        const val MAX_IGNITION_CHAIN_DEPTH = 16
        const val CLAIM_WINDOW_MILLIS = 500L
    }
}
