package com.tracel.plugin.api.addon

import com.tracel.api.actor.Actor
import com.tracel.api.addon.AddonRecorder
import com.tracel.api.addon.BlockBatch
import com.tracel.api.item.ItemHolder
import com.tracel.api.result.Res
import com.tracel.api.result.getOrElse
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import com.tracel.api.write.WriteError
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockShape
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.item.toItemTotals
import com.tracel.plugin.actor.ActorHolders
import com.tracel.plugin.api.Owned
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.api.write.TracelAttribution
import com.tracel.plugin.integration.worldedit.EditBuffer
import com.tracel.plugin.rollback.outside.OutsideReconciler
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.log.Warnings
import kotlinx.coroutines.launch
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("TracelAddons")

private const val MAX_BATCH = 4_000

/** [AddonWrites] over the running plugin's capture. */
internal class ServiceWrites(
    private val services: TracelServices,
    private val extensions: Extensions,
    private val running: () -> Boolean,
) : AddonWrites {
    override fun recorder(addon: String, owned: Owned): AddonRecorder = Recorder(owned)

    private val reconciler = OutsideReconciler(services.rolledBack, services.outside, services.scope) { kind ->
        extensions.reversibles[kind]
    }

    override fun reconcile(kind: String) {
        services.scope.launch { reconciler.reconcileKind(kind) }
    }

    override suspend fun record(
        addon: String,
        name: NamespacedName,
        text: String,
        by: Actor?,
        at: BlockPlace?,
    ): Res<Unit, WriteError> = with(TracelAttribution(services, addon, running)) { record(name, by, at, text) }

    private inner class Recorder(private val owned: Owned) : AddonRecorder {
        override fun <T> watching(holder: ItemHolder.Custom, player: Player?, action: () -> T): T {
            val id = HolderId.Custom(holder.kind.toString(), holder.id)
            val bound = checkNotNull(extensions.holder(id)) {
                "${holder.kind} ${holder.id} is not a holder anyone registered, or its ID no longer decodes."
            }
            val at = player?.toBlockPos() ?: bound.place?.let(ApiConversions::pos)
            booking("read ${holder.kind} before a move.") {
                bound.read()?.let { services.material.seedOnOpen(id, it.toItemTotals(), at, baseline = true) }
            }
            val result = action()
            booking("write down a move in ${holder.kind}") {
                val after = bound.read()?.toItemTotals().orEmpty()
                if (player != null) {
                    services.material.reconcile(listOf(player.inventory), player, extra = mapOf(id to after))
                } else {
                    services.material.reconcile(id, after, CauseKind.PLUGIN, null, at)
                }
            }
            return result
        }

        override fun batch(by: Actor): BlockBatch {
            val signer = ActorHolders.writer(by, signer = null).getOrElse { error(it.toString()) }
            return owned.own(Batch(signer))
        }
    }

    private inner class Batch(private val by: HolderId) : BlockBatch {
        private val buffers = LinkedHashMap<UUID, EditBuffer>()
        private var noted = 0
        private var ticket: Long? = null
        private var since = 0L
        private var closed = false

        override fun block(place: BlockPlace, before: BlockData, after: BlockData) {
            val full = synchronized(this) {
                if (closed) return
                if (ticket == null) {
                    ticket = services.pendingCaptures.owed()
                    since = System.currentTimeMillis()
                }
                buffers.getOrPut(place.world) { EditBuffer(WorldId(place.world)) }
                    .note(place.x, place.y, place.z, shape(before), shape(after))
                ++noted >= MAX_BATCH
            }
            if (full) flush()
        }

        override fun flush() {
            val drained: List<Pair<WorldId, Map<ActionKind, List<BlockEdit>>>>
            val owed: Long
            val at: Long
            synchronized(this) {
                owed = ticket ?: return
                drained = buffers.map { (world, buffer) -> WorldId(world) to buffer.drain() }
                at = since
                ticket = null
                noted = 0
            }
            try {
                for ((world, batch) in drained) {
                    for ((action, edits) in batch) services.shape.edits(action, CauseKind.PLUGIN, by, world, edits, at)
                }
            } catch (failure: Throwable) {
                logger.log(Level.WARNING, "An addon's block batch could not be written down.", failure)
            } finally {
                services.pendingCaptures.done(owed)
            }
        }

        override fun close() {
            synchronized(this) {
                if (closed) return
                closed = true
            }
            flush()
        }

        private fun shape(data: BlockData) = BlockShape(BlockDataKey(data.asString))
    }

    private inline fun booking(what: String, work: () -> Unit) {
        try {
            work()
        } catch (failure: Throwable) {
            Warnings.once(logger, "addon-book:$what") { "Tracel could not $what: $failure" }
            logger.log(Level.FINE, "could not $what", failure)
        }
    }
}
