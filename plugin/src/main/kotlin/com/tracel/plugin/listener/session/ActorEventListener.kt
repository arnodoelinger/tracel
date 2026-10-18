package com.tracel.plugin.listener.session

import com.tracel.annotations.Observes
import com.tracel.annotations.Unstable
import com.tracel.model.event.ActorEvent
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.entity.HitActor
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.util.concurrent.ExpiringMap
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerTradeEvent
import kotlin.math.roundToLong
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Beacon
import org.bukkit.block.Block
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.FishHook
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Minecart
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private class BeaconSession(val block: Block, val before: Pair<String?, String?>)

/**
 * The event log: what players say and type, when they come and go, how they die, what they shoot,
 * where they sleep, trade and teleport, and which carts they push.
 *
 * Nothing here is ever rolled back.
 */
class ActorEventListener(services: TracelServices) : TracelListener(services) {
    private val beaconBefore = ConcurrentHashMap<UUID, BeaconSession>()

    private val pushed = ExpiringMap<Pair<UUID, UUID>, Boolean>(PUSH_QUIET_MILLIS, 4_096)

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
        if (victim is ItemFrame && damager is Player && !victim.item.type.isAir) {
            record(EventKind.SET, damager, "frame took ${victim.item.type.key.key}", victim.toBlockPos())
            return
        }
        val attacker = shooter ?: (damager as? Player)?.takeIf { victim is LivingEntity } ?: return
        if (victim !is Player && !services.logEntityDamage) return
        val victimName = (victim as? Player)?.let { "@${it.name}" } ?: victim.type.key.key
        val how = if (shooter != null) " with ${damager.type.key.key}" else ""
        val hp = "%.1f".format(Locale.ROOT, event.finalDamage)
        val left = (victim as? LivingEntity)?.let { maxOf(0.0, it.health - event.finalDamage) }
            ?.let { ", ${"%.1f".format(Locale.ROOT, (it * 2).roundToLong() / 2.0)} left" }.orEmpty()
        record(EventKind.HIT, attacker, "$victimName$how ($hp hp$left)", victim.toBlockPos())
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

    @Observes
    fun onStand(event: PlayerArmorStandManipulateEvent) {
        val put = event.playerItem.type.takeUnless { it.isAir }?.key?.key ?: "none"
        val took = event.armorStandItem.type.takeUnless { it.isAir }?.key?.key ?: "none"
        if (put == "none" && took == "none") return
        val slot = event.slot.name.lowercase(Locale.ROOT)
        record(EventKind.SET, event.player, "stand $slot $put $took", event.rightClicked.toBlockPos())
    }

    @Observes
    fun onFrameUse(event: PlayerInteractEntityEvent) {
        val frame = event.rightClicked as? ItemFrame ?: return
        val inside = frame.item.type.takeUnless { it.isAir }?.key?.key
        if (inside != null) {
            if (event.hand == EquipmentSlot.HAND) {
                record(EventKind.SET, event.player, "frame rotated $inside", frame.toBlockPos())
            }
            return
        }
        val held = event.player.inventory.getItem(event.hand).type.takeUnless { it.isAir }?.key?.key ?: return
        record(EventKind.SET, event.player, "frame put $held", frame.toBlockPos())
    }

    @Observes
    fun onPush(event: VehicleEntityCollisionEvent) {
        val cart = event.vehicle as? Minecart ?: return
        val player = event.entity as? Player ?: return
        if (cart.velocity.lengthSquared() > PUSHED_SPEED_SQ) return
        if (cart is ExplosiveMinecart) HitActor.hit(cart, HolderId.Player(player.uniqueId))
        if (!pushed.putIfAbsent(player.uniqueId to cart.uniqueId, true)) return
        record(EventKind.PUSH, player, cart.type.key.key, cart.toBlockPos())
    }

    @Observes
    fun onSleep(event: PlayerBedEnterEvent) {
        if (event.bedEnterResult != PlayerBedEnterEvent.BedEnterResult.OK) return
        record(EventKind.SLEEP, event.player, event.bed.type.key.key, event.bed.toBlockPos())
    }

    @Observes
    fun onTrade(event: PlayerTradeEvent) {
        val result = event.trade.result
        val price = event.trade.ingredients
            .filter { !it.type.isAir }
            .joinToString(", ") { "${it.type.key.key} x${it.amount}" }
        val trader = event.villager
        record(
            EventKind.TRADE,
            event.player,
            "${result.type.key.key} x${result.amount} for $price with ${trader.type.key.key}",
            trader.toBlockPos(),
        )
    }

    @Observes
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.cause !in TELEPORTS) return
        val to = event.to
        val world = to.world ?: return
        val from = event.from
        val sameSpot = from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ
        if (from.world == world && sameSpot) return
        record(
            EventKind.TELEPORT,
            event.player,
            event.cause.name.lowercase(Locale.ROOT),
            BlockPos(WorldId(world.uid), to.blockX, to.blockY, to.blockZ),
        )
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

        const val PUSH_QUIET_MILLIS = 10_000L
        const val PUSHED_SPEED_SQ = 0.04

        @Suppress("REMOVAL")
        val TELEPORTS = setOf(
            TeleportCause.COMMAND,
            TeleportCause.ENDER_PEARL,
            TeleportCause.CHORUS_FRUIT,
            TeleportCause.NETHER_PORTAL,
            TeleportCause.END_PORTAL,
            TeleportCause.END_GATEWAY,
        )
    }
}
