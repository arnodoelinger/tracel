package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.services.TracelServices
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Beacon
import org.bukkit.block.Block
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private class BeaconSession(val block: Block, val before: Pair<String?, String?>)

/**
 * The event log: what players say and type, when they come and go, how they die, and what they shoot.
 *
 * Nothing here is ever rolled back.
 */
class ActorEventListener(services: TracelServices) : TracelListener(services) {
    private val beaconBefore = ConcurrentHashMap<UUID, BeaconSession>()

    @Observes
    fun onChat(event: AsyncChatEvent) =
        record(EventKind.CHAT, event.player, PlainTextComponentSerializer.plainText().serialize(event.message()))

    @Observes
    @Unstable
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        record(EventKind.COMMAND, event.player, redacted(event.message))
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

    @Observes
    fun onBedClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val bed = event.clickedBlock?.takeIf { it.blockData is Bed } ?: return
        record(EventKind.CLICK, event.player, bed.type.key.key, bed.toBlockPos())
    }

    @Observes
    fun onCompassClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val lodestone = event.clickedBlock?.takeIf { it.type == Material.LODESTONE } ?: return
        if (event.item?.type != Material.COMPASS) return
        record(EventKind.SET, event.player, "compass lodestone", lodestone.toBlockPos())
    }

    @Observes
    fun onBeaconClick(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || event.hand != EquipmentSlot.HAND) return
        val block = event.clickedBlock?.takeIf { it.type == Material.BEACON } ?: return
        beaconBefore[event.player.uniqueId] = BeaconSession(block, effects(block))
    }

    @Observes
    fun onBeaconClose(event: InventoryCloseEvent) {
        if (event.inventory.type != InventoryType.BEACON) return
        val player = event.player as? Player ?: return
        val session = beaconBefore.remove(player.uniqueId) ?: return
        val after = effects(session.block)
        if (after == session.before) return
        val (primary, secondary) = after
        record(
            EventKind.SET,
            player,
            "beacon ${primary ?: "none"} ${secondary ?: "none"}",
            session.block.toBlockPos(),
        )
    }

    @Observes
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_ENTITY) return
        val caught = event.caught ?: return
        val what = (caught as? Item)?.itemStack?.type?.key?.key ?: caught.type.key.key
        record(EventKind.PULL, event.player, what, caught.toBlockPos())
    }

    private fun effects(block: Block): Pair<String?, String?> {
        val beacon = block.getState(false) as? Beacon ?: return null to null
        return beacon.primaryEffect?.type?.key?.key to beacon.secondaryEffect?.type?.key?.key
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
