package com.tracel.plugin.listener.world.cell

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.util.ParsedBlockPos
import com.tracel.plugin.util.CommandOrigin
import com.tracel.plugin.util.toCommandOrigin
import com.tracel.plugin.util.ownsChunkAt
import com.tracel.plugin.util.parseBlockPos
import com.tracel.plugin.util.tokenize
import com.tracel.plugin.util.Warnings
import java.util.logging.Logger
import org.bukkit.Bukkit
import org.bukkit.GameRules
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent

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
        when (nameAndRest.getOrNull(0)?.lowercase()?.removePrefix("minecraft:")) {
            "setblock" -> handleSetblock(rest, world, causedBy, cause, at)
            "fill" -> handleFill(rest, world, causedBy, cause, at)
            "clone" -> handleClone(rest, world, causedBy, cause, at)
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

/** Vanilla refuses `/fill` and `/clone` past this; capturing more would log a no-op. */
@Suppress("DEPRECATION")
internal fun World.blockModificationLimit(): Int =
    getGameRuleValue(GameRules.MAX_BLOCK_MODIFICATIONS) // TODO: elvis?

/** Inclusive block AABB; max is exclusive, same as [BoundingBox.of] for two blocks. */
internal fun blockBox(from: ParsedBlockPos, to: ParsedBlockPos): BoundingBox =
    BoundingBox.of(from.vector(), to.vector()).expandDirectional(1.0, 1.0, 1.0)

/** `x` blocks. */
internal fun BoundingBox.xBlocks(): IntRange = minX.toInt() until maxX.toInt()

/** `y` blocks. */
internal fun BoundingBox.yBlocks(): IntRange = minY.toInt() until maxY.toInt()

/** `z` blocks. */
internal fun BoundingBox.zBlocks(): IntRange = minZ.toInt() until maxZ.toInt()

/** `null` if vanilla would refuse the `/fill` for [maxBlocks]. */
internal fun fillBox(from: ParsedBlockPos, to: ParsedBlockPos, maxBlocks: Int): BoundingBox? {
    val box = blockBox(from, to)
    return if (box.volume > maxBlocks) null else box
}

/**
 * Destination of `/clone` only.
 *
 * Source is read-only and must not be captured.
 */
internal fun cloneDestBox(
    srcFrom: ParsedBlockPos,
    srcTo: ParsedBlockPos,
    dstOrigin: ParsedBlockPos,
    maxBlocks: Int,
): BoundingBox? {
    val src = blockBox(srcFrom, srcTo)
    if (src.volume > maxBlocks) return null
    return src.shift(dstOrigin.x - src.minX, dstOrigin.y - src.minY, dstOrigin.z - src.minZ)
}
