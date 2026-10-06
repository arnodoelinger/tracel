package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.adapter.command.toCommandOrigin
import com.tracel.plugin.adapter.world.ownsChunkAt
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.specifics.command.StructureCommand
import com.tracel.plugin.specifics.command.VANILLA_NAMESPACE
import com.tracel.plugin.util.command.CommandOrigin
import com.tracel.plugin.util.command.parseBlockPos
import com.tracel.plugin.util.command.tokenize
import com.tracel.plugin.util.log.Warnings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.util.BoundingBox
import java.util.logging.Logger

/**
 * `/setblock`, `/fill`, `/clone` write blocks without place / break events
 * listener.
 *
 * Command blocks are not supported yet. That's why it's unstable.
 */
@Unstable
class StructureCommandListener(services: TracelServices) : TracelListener(services) {
    private val logger = Logger.getLogger("StructureCommandListener")

    @Observes
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val body = bodyOf(event.message) ?: return
        val loc = event.player.location
        handle(
            body = body,
            causedBy = HolderId.Player(event.player.uniqueId),
            cause = CauseKind.PLAYER_ACTION,
            origin = event.player.toCommandOrigin(),
            world = loc.world
        )
    }

    /** Players already hit [onPlayerCommand]; this is `stdin` only. */
    @Observes
    fun onServerCommand(event: ServerCommandEvent) {
        if (event.sender is Player) return
        handleConsole(event.command)
    }

    /** Listen [RemoteServerCommandEvent]. */
    @Observes
    fun onRemoteCommand(event: RemoteServerCommandEvent) = handleConsole(event.command)

    private fun handleConsole(command: String) {
        val body = bodyOf("/$command") ?: return
        val world = Bukkit.getWorlds().firstOrNull() ?: return
        handle(
            body = body,
            causedBy = null,
            cause = CauseKind.WORLD,
            origin = null,
            world = world
        )
    }

    private fun handle(body: String, causedBy: HolderId?, cause: CauseKind, origin: CommandOrigin?, world: World) {
        if (restoring) return
        val prefix = EXECUTE_RUN.find(body)?.value
        if (prefix != null && EXECUTE_ELSEWHERE.containsMatchIn(prefix)) return
        val moved = prefix != null && EXECUTE_MOVES.containsMatchIn(prefix)
        val effective = if (prefix != null) body.substring(prefix.length) else body
        val at = if (moved) null else origin
        val nameAndRest = effective.trim().split(Regex("\\s+"), limit = 2)
        val rest = nameAndRest.getOrNull(1) ?: ""
        when (StructureCommand.named(nameAndRest.getOrNull(0)?.lowercase()?.removePrefix(VANILLA_NAMESPACE))) {
            StructureCommand.SETBLOCK -> handleSetblock(rest, world, causedBy, cause, at)
            StructureCommand.FILL -> handleFill(rest, world, causedBy, cause, at)
            StructureCommand.CLONE -> handleClone(rest, world, causedBy, cause, at)
            null -> Unit
        }
    }

    private fun handleSetblock(
        rest: String,
        world: World,
        causedBy: HolderId?,
        cause: CauseKind,
        origin: CommandOrigin?
    ) {
        val tokens = tokenize(rest, POS_TOKENS)
        val pos = parseBlockPos(tokens, 0, origin) ?: return
        captureRegion(world, blockBox(pos, pos), causedBy, cause)
    }

    private fun handleFill(rest: String, world: World, causedBy: HolderId?, cause: CauseKind, origin: CommandOrigin?) {
        val tokens = tokenize(rest, POS_TOKENS * 2)
        val from = parseBlockPos(tokens, 0, origin) ?: return
        val to = parseBlockPos(tokens, POS_TOKENS, origin) ?: return
        val box = fillBox(from, to, world.blockModificationLimit()) ?: return
        captureRegion(world, box, causedBy, cause)
    }

    private fun handleClone(rest: String, world: World, causedBy: HolderId?, cause: CauseKind, origin: CommandOrigin?) {
        val tokens = tokenize(rest, POS_TOKENS * 3)
        val srcFrom = parseBlockPos(tokens, 0, origin) ?: return
        val srcTo = parseBlockPos(tokens, POS_TOKENS, origin) ?: return
        val dstOrigin = parseBlockPos(tokens, POS_TOKENS * 2, origin) ?: return
        val box = cloneDestBox(srcFrom, srcTo, dstOrigin, world.blockModificationLimit()) ?: return
        captureRegion(world, box, causedBy, cause)
        if (rest.split(Regex("\\s+")).any { it.equals("move", ignoreCase = true) }) {
            fillBox(srcFrom, srcTo, world.blockModificationLimit())?.let { captureRegion(world, it, causedBy, cause) }
        }
    }

    // Folia: one region scheduler hop per chunk
    private fun captureRegion(world: World, box: BoundingBox, causedBy: HolderId?, cause: CauseKind) {
        val xs = box.xBlocks()
        val ys = box.yBlocks()
        val zs = box.zBlocks()
        if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) return

        // 1 chunk = 16 blocks, i.e., x / 16 or x >> 4
        for (cx in (xs.first shr 4)..(xs.last shr 4)) {
            for (cz in (zs.first shr 4)..(zs.last shr 4)) {
                val x0 = maxOf(xs.first, cx shl 4)
                val x1 = minOf(xs.last, (cx shl 4) + 15) // (cx * 16 + 15)
                val z0 = maxOf(zs.first, cz shl 4)
                val z1 = minOf(zs.last, (cz shl 4) + 15) //
                val blocks = ArrayList<Block>((x1 - x0 + 1) * (ys.last - ys.first + 1) * (z1 - z0 + 1))
                for (x in x0..x1) for (y in ys) for (z in z0..z1) {
                    blocks += world.getBlockAt(x, y, z)
                }
                if (ownsChunkAt(world, x0, z0)) {
                    shape.reread(ActionKind.BLOCK_CHANGE, cause, causedBy, blocks)
                    continue
                }
                Warnings.once(logger, "command-hop") {
                    "a /fill, /setblock or /clone reached chunks another region owns; they are read after the command ran"
                }
                val ticket = services.pendingCaptures.owed()
                val at = Location(world, x0.toDouble(), ys.first.toDouble(), z0.toDouble())
                Bukkit.getRegionScheduler().execute(services.plugin, at) {
                    try {
                        shape.reread(ActionKind.BLOCK_CHANGE, cause, causedBy, blocks)
                    } finally {
                        services.pendingCaptures.done(ticket)
                    }
                }
            }
        }
    }

    private fun bodyOf(message: String): String? = if (message.startsWith("/")) message.substring(1) else message

    private companion object {
        const val POS_TOKENS = 3
        val EXECUTE_RUN = Regex("^(minecraft:)?execute\\b.*?\\brun\\s+", RegexOption.IGNORE_CASE)
        val EXECUTE_MOVES = Regex("\\b(at|positioned|align|anchored|facing|rotated)\\b", RegexOption.IGNORE_CASE)
        val EXECUTE_ELSEWHERE = Regex("\\bin\\s+\\S+", RegexOption.IGNORE_CASE)
    }
}
