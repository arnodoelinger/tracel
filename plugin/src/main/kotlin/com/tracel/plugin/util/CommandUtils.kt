package com.tracel.plugin.util

import com.tracel.plugin.i18n.say
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.*
import kotlin.math.floor

/** @return the sender as a [Player], or sends [message] and returns `null`. */
fun CommandSender.requirePlayer(message: Component): Player? {
    val player = this as? Player
    if (player == null) say(message)
    return player
}

/**
 * Resolves an offline or online player name to their UUID.
 *
 * @return `null` if no record of a player by that name exists.
 */
fun resolvePlayerUuid(name: String): UUID? {
    if (name.isBlank()) return null
    Bukkit.getPlayerExact(name)?.let { return it.uniqueId }
    val offline = Bukkit.getOfflinePlayerIfCached(name) ?: return null
    return if (offline.hasPlayedBefore()) offline.uniqueId else null
}

/** A block coordinate parsed from command tokens. */
internal data class ParsedBlockPos(val x: Int, val y: Int, val z: Int) {
    fun vector(): Vector = Vector(x.toDouble(), y.toDouble(), z.toDouble())
}

/**
 * Resolves three tokens at [index] against [origin]: absolute or `~` / `~n`.
 *
 * @return `null` for `^` (look-relative) — that needs yaw / pitch, and a wrong axis
 * would capture the wrong block. Uncaptured beats guessed.
 */
internal fun parseBlockPos(tokens: List<String>, index: Int, origin: CommandOrigin?): ParsedBlockPos? {
    if (index < 0 || index + 3 > tokens.size) return null
    val x = parseAxis(tokens[index], origin?.x) ?: return null
    val y = parseAxis(tokens[index + 1], origin?.y) ?: return null
    val z = parseAxis(tokens[index + 2], origin?.z) ?: return null
    return ParsedBlockPos(floorToBlock(x), floorToBlock(y), floorToBlock(z))
}

/**
 * Cap [max] before the block argument: `[state]{nbt}` can contain whitespace.
 *
 * The placed block is read off the world after the command runs.
 */
internal fun tokenize(body: String, max: Int): List<String> {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return emptyList()
    return trimmed.split(Regex("\\s+"), limit = max + 1)
}

private fun parseAxis(token: String, originValue: Double?): Double? = when {
    token == "~" -> originValue
    token.startsWith("~") -> originValue?.let { o -> token.substring(1).toDoubleOrNull()?.plus(o) }
    token.startsWith("^") -> null
    else -> token.toDoubleOrNull()
}

private fun floorToBlock(value: Double): Int = floor(value).toInt()
