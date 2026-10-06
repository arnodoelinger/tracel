package com.tracel.plugin.adapter.rollback.structure.block.check

import com.tracel.model.world.BlockPos
import com.tracel.model.world.WorldId
import com.tracel.plugin.adapter.world.ownsChunkAt
import com.tracel.plugin.util.geometry.chunkKey
import com.tracel.plugin.util.geometry.chunkKeyX
import com.tracel.plugin.util.geometry.chunkKeyZ
import org.bukkit.World
import org.bukkit.entity.Hanging
import org.bukkit.entity.LeashHitch
import java.util.*
import kotlin.math.floor

/** A painting's box stops a hair short of the next cell. Without the inset it would claim that cell too */
private const val INSET = 1.0E-3

/** Cells a painting or item frame that stays sits in. A solid block written there pops it, drop and all. */
internal fun hangingCells(world: World, chunks: Set<Long>, gone: Set<UUID>): Set<BlockPos> {
    val out = HashSet<BlockPos>()
    val id = WorldId(world.uid)
    val around = HashSet<Long>()
    for (key in chunks) for (dx in -1..1) for (dz in -1..1) around += chunkKey(
        (chunkKeyX(key) + dx) shl 4,
        (chunkKeyZ(key) + dz) shl 4
    )
    for (key in around) {
        if (!world.isChunkLoaded(chunkKeyX(key), chunkKeyZ(key)) || !ownsChunkAt(
                world,
                chunkKeyX(key) shl 4,
                chunkKeyZ(key) shl 4
            )
        ) continue
        for (entity in world.getChunkAt(chunkKeyX(key), chunkKeyZ(key)).entities) {
            if (entity !is Hanging || entity is LeashHitch || entity.uniqueId in gone) continue
            val box = entity.boundingBox
            for (x in floor(box.minX + INSET).toInt()..floor(box.maxX - INSET).toInt()) {
                for (y in floor(box.minY + INSET).toInt()..floor(box.maxY - INSET).toInt()) {
                    for (z in floor(box.minZ + INSET).toInt()..floor(box.maxZ - INSET).toInt()) out += BlockPos(
                        id,
                        x,
                        y,
                        z
                    )
                }
            }
        }
    }
    return out
}
