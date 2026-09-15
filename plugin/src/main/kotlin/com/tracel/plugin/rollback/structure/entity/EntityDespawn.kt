package com.tracel.plugin.rollback.structure.entity

import com.tracel.annotations.Unstable
import com.tracel.engine.rollback.structure.StructureStep
import com.tracel.model.id.WorldId
import com.tracel.model.world.entity.EntityShape
import com.tracel.plugin.adapter.entity.cargoStacks
import com.tracel.plugin.adapter.entity.emptyCargo
import com.tracel.plugin.adapter.entity.sittingAt
import com.tracel.plugin.adapter.entity.takeOffShoulder
import com.tracel.plugin.adapter.entity.unleash
import com.tracel.plugin.adapter.entity.unleashHeld
import com.tracel.plugin.rollback.structure.StructureRestorer
import com.tracel.plugin.util.Warnings
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
import java.util.UUID

// TODO: rewrite, should be improved

@Unstable
internal fun StructureRestorer.despawn(
    world: World,
    step: StructureStep.RemoveEntity,
    ledgerCargoFor: Set<UUID>,
    ledgerHeldBy: Set<UUID>,
): Despawn {
    world.getChunkAt(step.at.x shr 4, step.at.z shr 4)
    val here = services.whereabouts.at(step.entity)
    val loc = if (here != null && here.world == WorldId(world.uid)) {
        Location(world, here.x + 0.5, here.y.toDouble(), here.z + 0.5)
    } else {
        Location(world, step.shape.x, step.shape.y, step.shape.z)
    }
    val entity = Bukkit.getEntity(step.entity)
        ?: step.shape.sittingAt(world, loc, step.entity)
        ?: world.getNearbyEntities(loc, 2.5, 2.5, 2.5).firstOrNull { it.uniqueId == step.entity }
        ?: return if (takeOffShoulder(step.entity)) Despawn.Removed(step.entity)
        else if (step.shape.isLeashKnot()) Despawn.Removed(step.entity) else Despawn.Absent

    val cargo = runCatching { entity.cargoStacks() }.getOrDefault(emptyList())
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
    return Despawn.Removed(entity.uniqueId)
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

private fun EntityShape.isLeashKnot(): Boolean =
    type.value.substringAfter(':') == "leash_knot"
