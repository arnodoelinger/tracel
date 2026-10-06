package com.tracel.plugin.listener.support.cell

import com.tracel.model.world.BlockPos
import com.tracel.plugin.adapter.block.toBlockPos
import com.tracel.plugin.util.concurrent.ExpiringMap
import org.bukkit.block.Block
import java.util.*

/** Who last clicked a dragon egg. */
internal object DragonEggCell {
    private const val TTL_MS = 5_000L

    private val byEgg = ExpiringMap<BlockPos, UUID>(TTL_MS, 1_024)

    fun clicked(egg: Block, player: UUID) {
        byEgg.put(egg.toBlockPos(), player)
    }

    fun lastAt(egg: Block): UUID? = byEgg[egg.toBlockPos()]
}
