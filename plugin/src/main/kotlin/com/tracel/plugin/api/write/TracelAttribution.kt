package com.tracel.plugin.api.write

import com.tracel.api.actor.Actor
import com.tracel.api.result.Res
import com.tracel.api.result.getOrElse
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import com.tracel.api.write.Attribution
import com.tracel.api.write.QuietWrites
import com.tracel.api.write.WriteError
import com.tracel.model.cause.CauseKind
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.Quantity
import com.tracel.model.world.ActionKind
import com.tracel.plugin.actor.ActorHolders
import com.tracel.plugin.adapter.block.toShape
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.bukkit.Bukkit
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * [Attribution] for one plugin: every write changes the world and books it in the same step, with
 * [CauseKind.PLUGIN] as the cause and [signer] as whoever did it when nobody else is named.
 */
internal class TracelAttribution(
    private val services: TracelServices,
    private val signer: String,
    private val alive: () -> Boolean,
) : Attribution {
    override suspend fun BlockPlace.set(data: BlockData, by: Actor?): Res<Unit, WriteError> {
        if (!alive()) return Res.Err(WriteError.Revoked)
        val causedBy = holderOf(by).getOrElse { return Res.Err(it) }
        val world = Bukkit.getWorld(world) ?: return Res.Err(WriteError.Unloaded(this))
        val wanted = data.clone()
        val at = ApiConversions.pos(this)
        return guarded {
            withContext(services.schedulers.region(HolderId.Block(at))) {
                val block = world.getBlockAt(x, y, z)
                val wasAir = block.type.isAir
                val before = block.toShape()
                services.selfManagedWorld.whileRestoring { block.setBlockData(wanted, true) }
                val action = when {
                    block.type.isAir -> ActionKind.BLOCK_BREAK
                    wasAir -> ActionKind.BLOCK_PLACE
                    else -> ActionKind.BLOCK_CHANGE
                }
                services.shape.edit(block, before, block.toShape(), action, CauseKind.PLUGIN, causedBy)
                Res.Ok(Unit)
            }
        }
    }

    override suspend fun Player.give(stack: ItemStack, by: Actor?): Res<Int, WriteError> {
        if (!alive()) return Res.Err(WriteError.Revoked)
        if (!isOnline) return Res.Err(WriteError.Offline)
        val causedBy = holderOf(by).getOrElse { return Res.Err(it) }
        val given = stack.clone()
        if (given.isEmpty) return Res.Ok(0)
        val player = this
        return guarded {
            withContext(services.schedulers.entity(uniqueId)) {
                if (!player.isOnline) return@withContext Res.Err(WriteError.Offline)
                val key = given.toItemKey()
                val asked = given.amount
                val left = player.inventory.addItem(given).values.sumOf { it.amount }
                val put = (asked - left).toLong()
                if (put > 0) {
                    val holder = HolderId.Player(player.uniqueId)
                    services.material.adjust(holder, key, put)
                    val flow = Flow(key, Quantity(put), HolderId.Source(SourceKind.PLUGIN), holder, FlowKind.MINT)
                    services.material.direct(listOf(flow), CauseKind.PLUGIN, causedBy, player.toBlockPos())
                }
                Res.Ok(left)
            }
        }
    }

    override suspend fun record(
        name: NamespacedName,
        by: Actor?,
        at: BlockPlace?,
        text: String?,
    ): Res<Unit, WriteError> {
        if (!alive()) return Res.Err(WriteError.Revoked)
        val causedBy = holderOf(by).getOrElse { return Res.Err(it) }
        val millis = System.currentTimeMillis()
        return guarded {
            val ticket = services.pendingCaptures.owed()
            try {
                services.events.append(
                    ActorEvent(
                        services.counters.nextSeq(), EventKind.CUSTOM, causedBy, millis, at?.let(ApiConversions::pos),
                        ApiConversions.customText(name, text),
                    ),
                )
                Res.Ok(Unit)
            } finally {
                services.pendingCaptures.done(ticket)
            }
        }
    }

    private fun holderOf(by: Actor?): Res<HolderId, WriteError> = ActorHolders.writer(by, signer)

    private suspend fun <T> guarded(work: suspend () -> Res<T, WriteError>): Res<T, WriteError> = try {
        work()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Res.Err(WriteError.Failed(failure.message ?: failure.javaClass.simpleName))
    }
}

/** [QuietWrites] for one plugin: the same guard a rollback writes behind, so listeners look away. */
internal class TracelQuiet(
    private val services: TracelServices,
    private val alive: () -> Boolean,
) : QuietWrites {
    override fun <T> quietly(action: () -> T): Res<T, WriteError> {
        if (!alive()) return Res.Err(WriteError.Revoked)
        return Res.Ok(services.selfManagedWorld.whileRestoring(action))
    }
}
