package com.tracel.plugin.adapter.rollback.structure.entity

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.entity.cargoStacks
import com.tracel.plugin.adapter.entity.emptyCargo
import com.tracel.plugin.adapter.entity.link.takeOffShoulder
import com.tracel.plugin.adapter.entity.link.unleash
import com.tracel.plugin.adapter.entity.link.unleashHeld
import com.tracel.plugin.adapter.entity.sittingAt
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.rollback.structure.entity.Despawn
import com.tracel.plugin.util.log.Warnings
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Snowable
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.LeashHitch
import org.bukkit.entity.Snowman
import java.util.*

@Unstable
internal fun StructureRestorer.despawn(
    world: World,
    step: StructureStep.RemoveEntity,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
): Despawn {
    val here = services.whereabouts.at(step.entity)
    val loc = if (here != null && here.world == WorldId(world.uid)) {
        Location(world, here.x + 0.5, here.y.toDouble(), here.z + 0.5)
    } else {
        Location(world, step.shape.x, step.shape.y, step.shape.z)
    }
    val entity = Bukkit.getEntity(step.entity)
        ?: step.shape.sittingAt(world, loc, step.entity)
        ?: world.getNearbyEntities(loc, 2.5, 2.5, 2.5).firstOrNull { it.uniqueId == step.entity }
        ?: return if (step.shape.type.value.endsWith("parrot") && takeOffShoulder(step.entity, loc)) Despawn.Removed(
            step.entity
        ) else Despawn.Absent

    // Found anywhere is not ours to touch: another region owns it now, and a removal from here is swallowed
    if (!Bukkit.isOwnedByCurrentRegion(entity)) {
        return Despawn.Refused("the ${entity.type.name.lowercase()} has moved into another region since; roll it back from there")
    }

    // Unreadable is not empty: removing the hull would take whatever it carries along
    val cargo = runCatching { entity.cargoStacks() }.getOrElse {
        return Despawn.Refused("could not read what the ${entity.type.name.lowercase()} carries: ${it.message ?: it::class.java.simpleName}")
    }
    if (cargo.isNotEmpty()) {
        val what = cargo.joinToString(", ") { "${it.type.name.lowercase()} x${it.amount}" }
        if (entity.uniqueId in ledgerCargoFor) {
            return Despawn.Refused("${entity.type.name.lowercase()} still holds material nobody withdrew: $what")
        }
        // Ledger has an account this plan never reached
        if (entity.uniqueId in ledgerHeldBy) {
            return Despawn.Refused(
                "${entity.type.name.lowercase()} holds material this rollback did not plan to " +
                        "withdraw: $what",
            )
        }
        // Unbooked
        Warnings.once(logger, "unbooked-cargo:${entity.type}") {
            "a ${entity.type.name.lowercase()} (${entity.uniqueId}) holds $what with no Entity " +
                    "account; the hull is still removed"
        }
        entity.emptyCargo()
    }
    if (entity is FallingBlock) {
        entity.dropItem = false
        entity.cancelDrop = true
    }

    // Unleash both ends now
    entity.unleash()

    // Knots only
    if (entity is LeashHitch) entity.unleashHeld()
    entity.emptyCargo()
    clearTrailUnder(entity)
    runCatching { entity.remove() }

    // Only what really went is journaled: undo respawns every entity recorded as removed
    return if (entity.isValid) Despawn.Refused("the ${entity.type.name.lowercase()} could not be removed") else Despawn.Removed(
        entity.uniqueId
    )
}

private fun clearTrailUnder(entity: Entity) {
    if (entity !is Snowman) return
    runCatching {
        val here = entity.location.block
        for (block in listOf(here, here.getRelative(BlockFace.DOWN))) {
            if (block.type != Material.SNOW) continue
            block.setType(Material.AIR, false)
            unsnow(block.getRelative(BlockFace.DOWN))
        }
    }
}

private fun unsnow(block: Block) {
    val data = block.blockData as? Snowable ?: return
    if (!data.isSnowy) return
    data.isSnowy = false
    block.setBlockData(data, false)
}
