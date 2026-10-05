package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.listener.TracelListener
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.FishHook
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent
import java.util.Locale
import com.tracel.model.world.BlockPos
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * The event log: what players say and type, when they come and go, how they die, and what they shoot.
 *
 * Nothing here is ever rolled back.
 */
class ActorEventListener(services: TracelServices) : TracelListener(services) {
    @Observes
    fun onChat(event: AsyncChatEvent) =
        record(EventKind.CHAT, event.player, PlainTextComponentSerializer.plainText().serialize(event.message()))

    @Observes
    @Unstable
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        record(EventKind.COMMAND, event.player, redacted(event.message)) // TODO: improve secret logic
    }

    @Observes
    fun onJoin(event: PlayerJoinEvent) = record(EventKind.JOIN, event.player, null)

    @Observes
    fun onQuit(event: PlayerQuitEvent) = record(EventKind.QUIT, event.player, null)

    @Observes
    fun onDeath(event: PlayerDeathEvent) {
        val source = event.damageSource
        val falling = (source.directEntity as? FallingBlock)?.blockData?.material?.key?.key?.let { "falling $it" }
        val killer = (source.causingEntity as? Player)?.name
            ?: source.causingEntity?.type?.key?.key
            ?: falling
            ?: source.directEntity?.type?.key?.key
            ?: source.damageType.key.key
        record(EventKind.DEATH, event.player, killer.replace('_', ' '))
    }

    @Observes
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        if (projectile is FishHook) return
        val player = projectile.shooter as? Player ?: return
        record(EventKind.SHOOT, player, projectile.type.key.key)
    }

    @Observes
    fun onDamage(event: EntityDamageByEntityEvent) {
        val damager = event.damager
        val victim = event.entity
        val shooter = (damager as? Projectile)?.shooter as? Player
        val attacker = shooter ?: (damager as? Player)?.takeIf { victim is Player } ?: return
        val victimName = (victim as? Player)?.let { "@${it.name}" } ?: victim.type.key.key
        val how = if (shooter != null) " with ${damager.type.key.key}" else ""
        val hp = "%.1f".format(Locale.ROOT, event.finalDamage)
        record(EventKind.HIT, attacker, "$victimName$how ($hp hp)", victim.toBlockPos())
    }

    private fun redacted(line: String): String {
        val label = line.removePrefix("/").substringBefore(' ')
        val command = Bukkit.getCommandMap().getCommand(label.lowercase()) ?: return line
        if (!SECRET.containsMatchIn("${command.usage} ${command.description}")) return line
        return "/$label ***"
    }

    private fun record(kind: EventKind, player: Player, text: String?, where: BlockPos? = null) {
        val at = where ?: runCatching { player.toBlockPos() }.getOrNull()
        val millis = System.currentTimeMillis()
        owing {
            services.events.append(
                ActorEvent(
                    services.counters.nextSeq(),
                    kind,
                    HolderId.Player(player.uniqueId),
                    millis,
                    at,
                    text
                )
            )
        }
    }

    private companion object {
        val SECRET = Regex("pass(word|wd|phrase)?|\\bpin\\b|secret|token", RegexOption.IGNORE_CASE)
    }
}
