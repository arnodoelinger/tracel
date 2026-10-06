package com.tracel.plugin.listener.world.entity

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent
import com.tracel.annotations.Observes
import com.tracel.annotations.Priority
import com.tracel.annotations.Unstable
import com.tracel.engine.capture.world.EntityChange
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.model.world.entity.EntityShape
import com.tracel.plugin.adapter.entity.kind.SpawnKind
import com.tracel.plugin.adapter.entity.kind.isScenery
import com.tracel.plugin.adapter.entity.kind.logsWorldShape
import com.tracel.plugin.adapter.entity.link.SelfManagedLink
import com.tracel.plugin.adapter.entity.remember
import com.tracel.plugin.adapter.entity.toBlockPos
import com.tracel.plugin.adapter.entity.toShape
import com.tracel.plugin.listener.TracelListener
import com.tracel.plugin.listener.support.cell.ColumnCell
import com.tracel.plugin.listener.support.cell.DispenseCell
import com.tracel.plugin.listener.support.entity.DamageBlame
import com.tracel.plugin.listener.support.entity.HitActor
import com.tracel.plugin.listener.support.entity.damageBlame
import com.tracel.plugin.listener.support.entity.explosionActor
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.specifics.command.EntityCommand
import com.tracel.plugin.specifics.command.isCommand
import com.tracel.plugin.specifics.entity.isBlastSource
import com.tracel.plugin.specifics.entity.kind
import com.tracel.plugin.specifics.item.spawnsAnEntity
import com.tracel.plugin.util.concurrent.ExpiringMap
import com.tracel.plugin.util.concurrent.ExpiringSet
import io.papermc.paper.event.player.PlayerNameEntityEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import org.bukkit.NamespacedKey
import org.bukkit.entity.*
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.block.Action
import org.bukkit.event.block.TNTPrimeEvent
import org.bukkit.event.entity.*
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.player.*
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.vehicle.VehicleCreateEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.persistence.PersistentDataType

private const val RECENT_MS = 5_000L

private const val SUMMON_WINDOW_MS = 1_000L

private const val USE_MS = 1_500L

private const val USES_KEPT = 256

private const val USE_RADIUS_SQUARED = 9.0

private const val MILLIS_PER_TICK = 50L

private const val DYING_KEPT = 4_096

private const val SUMMON_FRESH_TICKS = 5

private const val SPAWN_REACH = 2

private const val SWEEP_REACH = 8.0

private val KILLED_BY_COMMAND = setOf(EntityRemoveEvent.Cause.DEATH, EntityRemoveEvent.Cause.DISCARD)

/** Entity spawn / remove. */
@Unstable
class EntityLifecycleListener(services: TracelServices) : TracelListener(services) {
    private val placedBy = ExpiringMap<UUID, Blame>(RECENT_MS)
    private val removedBy = ExpiringMap<UUID, Blame>(RECENT_MS)
    private val recordedSpawn = ExpiringSet<UUID>(RECENT_MS)
    private val recordedRemove = ExpiringSet<UUID>(RECENT_MS)

    private val dying = ExpiringMap<UUID, EntityShape>(RECENT_MS, capacity = DYING_KEPT)

    private val summoner = ExpiringMap<UUID, HolderId>(SUMMON_WINDOW_MS)
    private val killer = ExpiringMap<UUID, HolderId>(SUMMON_WINDOW_MS)

    private val touchedBy = ExpiringMap<UUID, Blame>(RECENT_MS)
    private val transformedBy = ExpiringMap<UUID, Blame>(RECENT_MS)

    private val madeByKey = NamespacedKey(services.plugin, "made_by")

    private class Use(val world: UUID, val x: Double, val y: Double, val z: Double, val at: Long)

    private val uses = ConcurrentHashMap<UUID, Use>()

    @Observes
    fun onUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK || !event.hasItem()) return
        val block = event.clickedBlock?.getRelative(event.blockFace) ?: return
        val now = System.currentTimeMillis()
        if (uses.size > USES_KEPT) uses.values.removeIf { now - it.at > USE_MS }
        uses[event.player.uniqueId] = Use(block.world.uid, block.x + 0.5, block.y + 0.5, block.z + 0.5, now)
    }

    private val unlogged = ConcurrentHashMap.newKeySet<String>()

    private fun noteUnlogged(entity: Entity) {
        val by = usedNear(entity.location) ?: return
        val type = entity.type.key.toString()
        if (!unlogged.add(type)) return
        val who = (by as? HolderId.Player)?.uuid
        services.plugin.logger.info(
            "$type (${entity.javaClass.interfaces.firstOrNull()?.simpleName ?: entity.javaClass.simpleName}) appeared " +
                    "beside $who using an item, and is not a kind the world log keeps."
        )
    }

    private fun usedNear(at: org.bukkit.Location): HolderId? {
        val world = at.world?.uid ?: return null
        val now = System.currentTimeMillis()
        return uses.entries
            .filter { (_, use) -> use.world == world && now - use.at <= USE_MS }
            .map { (player, use) ->
                val dx = use.x - at.x
                val dy = use.y - at.y
                val dz = use.z - at.z
                player to dx * dx + dy * dy + dz * dz
            }
            .filter { (_, distance) -> distance <= USE_RADIUS_SQUARED }
            .minByOrNull { (_, distance) -> distance }
            ?.let { (player, _) -> HolderId.Player(player) }
    }

    private fun markMadeBy(entity: Entity, by: HolderId?) {
        val player = by as? HolderId.Player ?: return
        runCatching {
            entity.persistentDataContainer.set(madeByKey, PersistentDataType.STRING, player.uuid.toString())
        }
    }

    private fun madeBy(entity: Entity): HolderId? = runCatching {
        entity.persistentDataContainer.get(madeByKey, PersistentDataType.STRING)
            ?.let { HolderId.Player(UUID.fromString(it)) }
    }.getOrNull()

    @Observes
    fun onSummonCommand(event: PlayerCommandPreprocessEvent) {
        val text = event.message
        val body = if (text.startsWith("/")) text.substring(1) else text
        val by = HolderId.Player(event.player.uniqueId)
        when {
            body.isCommand(EntityCommand.SUMMON.literal) -> summoner.put(event.player.world.uid, by)
            body.isCommand(EntityCommand.KILL.literal) -> killer.put(event.player.world.uid, by)
        }
    }

    @Observes
    fun onPlace(event: EntityPlaceEvent) {
        val by = event.player?.let { HolderId.Player(it.uniqueId) }
        if (by != null) placedBy.put(event.entity.uniqueId, Blame(by))
        record(ActionKind.ENTITY_SPAWN, event.entity, by, after = true)
    }

    @Observes
    fun onVehicleCreate(event: VehicleCreateEvent) {
        val vehicle = event.vehicle
        if (vehicle.ticksLived > 0) return
        val by = placedBy[vehicle.uniqueId]?.who ?: summoner[vehicle.world.uid]
        record(ActionKind.ENTITY_SPAWN, vehicle, by, after = true)
    }

    @Observes
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        val by = HolderId.Player(player.uniqueId)
        placedBy.put(event.entity.uniqueId, Blame(by))
        record(ActionKind.ENTITY_SPAWN, event.entity, by, after = true)
    }

    @Observes
    fun onSpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        if (entity !is Item && entity !is Player && !entity.logsWorldShape()) noteUnlogged(entity)
        if (runCatching { entity.entitySpawnReason }.getOrNull() == CreatureSpawnEvent.SpawnReason.COMMAND ||
            (event as? CreatureSpawnEvent)?.spawnReason == CreatureSpawnEvent.SpawnReason.COMMAND
        ) {
            summoner[entity.world.uid]?.let { markMadeBy(entity, it) }
        }
        if (!entity.logsWorldShape()) return
        if (restoring) return
        if (entity.uniqueId in recordedSpawn) return
        if (entity.ticksLived > 0) return
        val reason = (event as? CreatureSpawnEvent)?.spawnReason ?: runCatching { entity.entitySpawnReason }.getOrNull()
        val transformed = transformedBy.remove(entity.uniqueId)
        val dispensed =
            if (reason == CreatureSpawnEvent.SpawnReason.DISPENSE_EGG) DispenseCell.at(entity.location.block) else null
        if (dispensed != null) {
            markMadeBy(entity, dispensed)
            record(
                ActionKind.ENTITY_SPAWN,
                entity.uniqueId,
                entity.toShape(),
                entity.toBlockPos(),
                dispensed,
                after = true
            )
            return
        }
        if (!entity.isScenery() && transformed == null && reason.kind() == SpawnKind.World) return
        val uuid = entity.uniqueId
        val loc = entity.location.clone()
        val at = entity.toBlockPos()
        val falling = entity as? FallingBlock
        val spawned = entity.toShape()
        if (transformed != null || reason.kind() == SpawnKind.Immediate) {
            val by = transformed?.who
                ?: placedBy.remove(uuid)?.who
                ?: summoner[entity.world.uid]?.takeIf { reason == CreatureSpawnEvent.SpawnReason.COMMAND }
                ?: ColumnCell.playerAt(loc.block)?.let(HolderId::Player)
                ?: usedNear(loc)
            markMadeBy(entity, by)
            record(ActionKind.ENTITY_SPAWN, uuid, spawned, at, by, after = true)
            return
        }
        later(loc, ticks = 2L) {
            val placed = placedBy.remove(uuid)
            if (uuid in recordedSpawn) return@later
            val by = placed?.who
                ?: summoner[loc.world.uid]?.takeIf { falling == null }
                ?: falling?.let { ColumnCell.playerWhoDisturbed(it)?.let(HolderId::Player) }
                ?: ColumnCell.playerAt(loc.block)?.let(HolderId::Player)
                ?: usedNear(loc)
            record(ActionKind.ENTITY_SPAWN, uuid, spawned, at, by, after = true)
        }
    }

    @Observes(ignoreCancelled = false)
    fun onAddToWorld(event: EntityAddToWorldEvent) {
        val entity = event.entity
        if (!entity.isScenery()) return
        services.whereabouts.remember(entity)
    }

    @Observes
    fun onVehicleMove(event: VehicleMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.chunk.x == to.chunk.x && from.chunk.z == to.chunk.z && from.world == to.world) return
        services.whereabouts.remember(event.vehicle)
    }

    @Observes
    fun onExplosionDamage(event: EntityDamageByEntityEvent) {
        val cause = event.cause
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION &&
            cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
        ) {
            return
        }
        val victim = event.entity
        if (!victim.logsWorldShape()) return
        val who = services.explosionActor(event.damager)
            ?: services.redstoneTriggers.recentExplosionNear(victim.location)
        removedBy.put(victim.uniqueId, Blame(who, CauseKind.EXPLOSION))
    }

    private fun hitBy(entity: Entity, blame: DamageBlame) {
        val who = blame.who ?: return
        HitActor.hit(entity, who, projectile = blame.cause == CauseKind.PROJECTILE)
    }

    @Observes
    fun onVehicleDamaged(event: VehicleDamageEvent) {
        if (restoring) return
        val attacker = event.attacker ?: return
        val shooter = ((attacker as? Projectile)?.shooter as? Player)?.let { HolderId.Player(it.uniqueId) }
        val who = (attacker as? Player)?.let { HolderId.Player(it.uniqueId) } ?: shooter ?: return
        HitActor.hit(event.vehicle, who, projectile = shooter != null)
    }

    @Observes
    fun onDamaged(event: EntityDamageEvent) {
        if (restoring) return
        val entity = event.entity
        if (entity is ItemFrame) {
            hitBy(entity, services.damageBlame(event))
            return
        }
        if (entity is ArmorStand || entity is EnderCrystal || entity is ExplosiveMinecart) {
            val hit = services.damageBlame(event)
            val who = hit.who
            if (who != null) {
                hitBy(entity, hit)
                if (entity.logsWorldShape() && removedBy[entity.uniqueId]?.who == null) removedBy.put(
                    entity.uniqueId,
                    Blame(who, hit.cause.takeIf { it == CauseKind.PROJECTILE })
                )
            }
            if (entity !is ArmorStand) return
        }
        if (entity.isScenery() && entity !is LivingEntity) {
            hitBy(entity, services.damageBlame(event))
            return
        }
        if (entity !is LivingEntity || entity is Player) return
        if (!entity.logsWorldShape()) return
        if (entity.uniqueId in recordedRemove) return
        val blame = services.damageBlame(event)
        if (entity.health - event.finalDamage > 0.0) {
            if (services.logEntityDamage) reshaped(entity, blame.who, blame.cause)
            return
        }
        dying.put(entity.uniqueId, entity.toShape())
        if (blame.who == null || removedBy[entity.uniqueId]?.who != null) return
        removedBy.put(
            entity.uniqueId,
            Blame(blame.who, blame.cause.takeIf { it == CauseKind.EXPLOSION || it == CauseKind.PROJECTILE }),
        )
    }

    @Observes(ignoreCancelled = false)
    fun onPlayerAttack(event: PrePlayerAttackEntityEvent) {
        if (restoring) return
        val entity = event.attacked
        if (entity.isScenery()) HitActor.hit(entity, HolderId.Player(event.player.uniqueId))
    }

    @Observes
    fun onTame(event: EntityTameEvent) {
        val owner = (event.owner as? Player)?.let { HolderId.Player(it.uniqueId) }
        reshaped(event.entity, owner)
    }

    @Observes
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder as? Player ?: return
        placedBy.put(event.entity.uniqueId, Blame(HolderId.Player(breeder.uniqueId)))
    }

    @Unstable
    @Observes
    fun onSpawnItemUsed(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return
        if (event.item?.type?.spawnsAnEntity() != true) return
        val player = event.player
        ColumnCell.rememberAround(player.uniqueId, block, SPAWN_REACH)
        sweepForFreshSpawns(player, HolderId.Player(player.uniqueId))
    }

    private fun sweepForFreshSpawns(player: Player, by: HolderId) {
        later(player, ticks = 1L) {
            for (nearby in player.getNearbyEntities(SWEEP_REACH, SWEEP_REACH, SWEEP_REACH)) {
                if (nearby.uniqueId in recordedSpawn) continue
                if (!nearby.logsWorldShape()) continue

                // Age guard
                if (runCatching { nearby.ticksLived }.getOrDefault(Int.MAX_VALUE) > SUMMON_FRESH_TICKS) continue
                record(ActionKind.ENTITY_SPAWN, nearby, by, after = true)
            }
        }
    }

    @Observes
    fun onShear(event: PlayerShearEntityEvent) {
        touchedBy.put(event.entity.uniqueId, Blame(HolderId.Player(event.player.uniqueId)))
    }

    @Observes
    fun onTransform(event: EntityTransformEvent) {
        val entity = event.entity
        val who = (entity as? ZombieVillager)?.conversionPlayer?.let { HolderId.Player(it.uniqueId) }
            ?: touchedBy.remove(entity.uniqueId)?.who
            ?: madeBy(entity)
        if (who == null) {
            for (result in event.transformedEntities) recordedSpawn.add(result.uniqueId)
            return
        }
        val blame = Blame(who)
        removedBy.put(entity.uniqueId, blame)
        for (result in event.transformedEntities) transformedBy.put(result.uniqueId, blame)
    }

    @Unstable
    @Observes
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (!entity.logsWorldShape()) return
        val killer = entity.killer
        if (killer != null) {
            val shot = (entity.lastDamageCause as? EntityDamageByEntityEvent)?.damager is Projectile
            removedBy.put(
                entity.uniqueId,
                Blame(HolderId.Player(killer.uniqueId), CauseKind.PROJECTILE.takeIf { shot }),
            )
            return
        }
        if (!entity.diedInAnExplosion()) return
        val known = removedBy[entity.uniqueId]
        if (known?.who != null) return
        removedBy.put(
            entity.uniqueId,
            Blame(services.redstoneTriggers.recentExplosionNear(entity.location), CauseKind.EXPLOSION),
        )
    }

    @Observes
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        val attacker = event.attacker
        val near = services.redstoneTriggers.recentExplosionNear(event.vehicle.location)
        val explosion = attacker.isBlastSource() || (attacker == null && near != null)
        val shooter = (attacker as? Projectile)?.shooter as? Player
        val who = (attacker as? Player)?.let { HolderId.Player(it.uniqueId) }
            ?: shooter?.let { HolderId.Player(it.uniqueId) }
            ?: attacker?.let { services.explosionActor(it) }
            ?: near
        val cause = if (explosion) CauseKind.EXPLOSION else if (shooter != null) CauseKind.PROJECTILE else null
        if (who != null || cause != null) removedBy.put(event.vehicle.uniqueId, Blame(who, cause))
        record(ActionKind.ENTITY_REMOVE, event.vehicle, who, after = false, cause = cause)
    }

    @Observes
    fun onHangingBreak(event: HangingBreakEvent) {
        val byEntity = (event as? HangingBreakByEntityEvent)?.remover
        val explosion = event.cause == HangingBreakEvent.RemoveCause.EXPLOSION ||
                byEntity.isBlastSource()
        val shooter = (byEntity as? Projectile)?.shooter as? Player
        val who: HolderId? = (byEntity as? Player)?.let { HolderId.Player(it.uniqueId) }
            ?: shooter?.let { HolderId.Player(it.uniqueId) }
            ?: byEntity?.let { services.explosionActor(it) }
            ?: if (explosion) services.redstoneTriggers.recentExplosionNear(event.entity.location) else null
        val cause = if (explosion) CauseKind.EXPLOSION else if (shooter != null) CauseKind.PROJECTILE else null
        if (who != null || cause != null) removedBy.put(event.entity.uniqueId, Blame(who, cause))
        record(ActionKind.ENTITY_REMOVE, event.entity, who, after = false, cause = cause)
    }

    @Observes
    fun onArmorStand(event: PlayerArmorStandManipulateEvent) {
        reshaped(event.rightClicked, HolderId.Player(event.player.uniqueId))
    }

    @Unstable
    @Observes(ignoreCancelled = false)
    fun onRemove(event: EntityRemoveEvent) {
        val entity = event.entity
        if (entity is Item || entity is Player) return
        if (event.cause != EntityRemoveEvent.Cause.UNLOAD && event.cause != EntityRemoveEvent.Cause.PLAYER_QUIT) {
            services.whereabouts.forget(entity.uniqueId)
        }
        val blame = removedBy.remove(entity.uniqueId)
            ?: killer[entity.world.uid]?.takeIf { event.cause in KILLED_BY_COMMAND }?.let { Blame(it) }
            ?: HitActor.of(entity)?.takeIf { entity.isScenery() }
                ?.let { Blame(it, CauseKind.PROJECTILE.takeIf { HitActor.wasShot(entity.uniqueId) }) }
        if (!event.cause.kind().records(entity.isScenery(), blame?.who != null)) return
        if (entity is AbstractArrow && event.cause == EntityRemoveEvent.Cause.DESPAWN) return

        if (entity.isMidDetonation(event.cause)) return

        val exploded = blame?.cause == CauseKind.EXPLOSION ||
                event.cause == EntityRemoveEvent.Cause.EXPLODE
        val who = blame?.who
            ?: if (exploded) services.redstoneTriggers.recentExplosionNear(entity.location) else null
        val shot = !exploded && who != null && blame?.cause == CauseKind.PROJECTILE

        bornUnlogged(entity)
        if (who == null && !exploded && entity.isScenery() && event.cause == EntityRemoveEvent.Cause.DEATH) {
            removedByLateHit(entity)
            return
        }
        record(
            ActionKind.ENTITY_REMOVE, entity, who, after = false,
            cause = if (exploded) CauseKind.EXPLOSION else if (shot) CauseKind.PROJECTILE else null,
        )
    }

    private fun removedByLateHit(entity: Entity) {
        if (restoring || entity.uniqueId in recordedRemove) return
        val uuid = entity.uniqueId
        val living = dying.remove(uuid)
        val looks = living ?: entity.toShape()
        val at = living?.blockPos(entity.world.uid) ?: entity.toBlockPos()
        later(entity.location) {
            record(
                ActionKind.ENTITY_REMOVE, uuid, looks, at, HitActor.of(uuid), after = false,
                cause = CauseKind.PROJECTILE.takeIf { HitActor.wasShot(uuid) },
            )
        }
    }

    @Observes
    fun onTntPrime(event: TNTPrimeEvent) {
        val player = event.primingEntity as? Player ?: return
        ColumnCell.remember(player.uniqueId, event.block)
    }

    @Observes
    fun onLeash(event: PlayerLeashEntityEvent) {
        val by = HolderId.Player(event.player.uniqueId)
        val holder = event.leashHolder
        if (holder is LeashHitch) placedBy.put(holder.uniqueId, Blame(by))
        reshaped(event.entity, by)
    }

    @Observes
    fun onUnleash(event: EntityUnleashEvent) {
        if (SelfManagedLink.isOurs) return
        if (event.reason == EntityUnleashEvent.UnleashReason.LEASHED_GONE) return
        reshaped(event.entity, (event as? PlayerUnleashEntityEvent)?.let { HolderId.Player(it.player.uniqueId) })
    }

    @Observes(priority = Priority.HIGHEST, ignoreCancelled = false)
    fun onUnleashDrop(event: EntityUnleashEvent) {
        if (restoring || SelfManagedLink.isOurs) event.isDropLeash = false
    }

    @Observes
    fun onMount(event: EntityMountEvent) {
        if (SelfManagedLink.isOurs) return
        reshaped(event.entity, null)
    }

    @Observes
    fun onDismount(event: EntityDismountEvent) {
        if (SelfManagedLink.isOurs) return
        reshaped(event.entity, null)
    }

    @Observes
    fun onName(event: PlayerNameEntityEvent) {
        reshaped(event.entity, HolderId.Player(event.player.uniqueId))
    }

    @Observes
    fun onRightClickEntity(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked
        if (entity is Player) return
        if (!entity.logsWorldShape()) return
        if (entity is InventoryHolder || entity is ArmorStand) return
        reshaped(entity, HolderId.Player(event.player.uniqueId))
    }

    private fun bornUnlogged(entity: Entity) {
        if (restoring || entity.isScenery() || entity.uniqueId in recordedSpawn) return
        if (!entity.logsWorldShape()) return
        val reason = runCatching { entity.entitySpawnReason }.getOrNull()
        if (reason.kind() != SpawnKind.World) return
        val lived = runCatching { entity.ticksLived }.getOrDefault(0).toLong()
        val looks = dying[entity.uniqueId] ?: entity.toShape()
        shape.entity(
            EntityChange(
                action = ActionKind.ENTITY_SPAWN,
                cause = CauseKind.WORLD,
                causedBy = null,
                epochMillis = System.currentTimeMillis() - lived * MILLIS_PER_TICK,
                at = looks.blockPos(entity.world.uid),
                entity = entity.uniqueId,
                before = null,
                after = looks,
            )
        )
    }

    private fun reshaped(entity: Entity, by: HolderId?, why: CauseKind? = null) {
        if (restoring) return
        if (!entity.logsWorldShape()) return
        val before = entity.toShape()
        val at = entity.toBlockPos()
        val epochMillis = System.currentTimeMillis()
        val cause = why ?: if (by != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        later(entity) {
            val after = entity.toShape()
            if (before == after) return@later
            shape.entity(
                EntityChange(
                    ActionKind.ENTITY_CHANGE, cause, by, epochMillis,
                    at, entity.uniqueId, before, after,
                )
            )
        }
    }

    private fun seenFor(after: Boolean) = if (after) recordedSpawn else recordedRemove

    private fun record(
        action: ActionKind,
        entity: Entity,
        causedBy: HolderId?,
        after: Boolean,
        cause: CauseKind? = null,
    ) {
        if (entity is Item || entity is Player) return
        if (!entity.logsWorldShape()) return
        if (restoring) return
        if (entity.uniqueId in seenFor(after)) return
        if (after) markMadeBy(entity, causedBy)
        if (after) services.whereabouts.remember(entity) else services.whereabouts.forget(entity.uniqueId)
        val living = if (after) null else dying.remove(entity.uniqueId)
        record(
            action = action,
            uuid = entity.uniqueId,
            entityShape = living ?: entity.toShape(),
            at = living?.blockPos(entity.world.uid) ?: entity.toBlockPos(),
            causedBy = causedBy,
            after = after,
            cause = cause,
        )
    }

    private fun record(
        action: ActionKind,
        uuid: UUID,
        entityShape: EntityShape,
        at: BlockPos,
        causedBy: HolderId?,
        after: Boolean,
        cause: CauseKind? = null,
    ) {
        if (restoring) return
        if (!seenFor(after).add(uuid)) return
        if (after) {
            services.whereabouts.remember(
                uuid,
                HolderId.Block(at.world, at.x, at.y, at.z),
            )
        } else {
            services.whereabouts.forget(uuid)
        }
        val now = System.currentTimeMillis()
        val resolvedCause = cause
            ?: if (causedBy != null) CauseKind.PLAYER_ACTION else CauseKind.WORLD
        shape.entity(
            EntityChange(
                action = action,
                cause = resolvedCause,
                causedBy = causedBy,
                epochMillis = now,
                at = at,
                entity = uuid,
                before = if (after) null else entityShape,
                after = if (after) entityShape else null,
            )
        )
    }
}

private fun EntityShape.blockPos(world: UUID): BlockPos =
    BlockPos(WorldId(world), floor(x).toInt(), floor(y).toInt(), floor(z).toInt())

private data class Blame(val who: HolderId?, val cause: CauseKind? = null)
