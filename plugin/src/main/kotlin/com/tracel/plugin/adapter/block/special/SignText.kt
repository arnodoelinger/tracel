package com.tracel.plugin.adapter.block.special

import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta

/** What a sign said, read back from the tile extras the log kept for it. */
internal object SignText {
    private val plain = PlainTextComponentSerializer.plainText()

    fun of(shape: BlockShape): String? = runCatching {
        val extras = shape.extras as? BlockExtras.Opaque ?: return@runCatching null
        val meta = ItemStack.deserializeBytes(extras.bytes).itemMeta as? BlockStateMeta ?: return@runCatching null
        val sign = meta.blockState as? Sign ?: return@runCatching null
        fun side(side: Side) = sign.getSide(side).lines()
            .map { plain.serialize(it).trim() }
            .filter(String::isNotEmpty)
            .joinToString(" / ")
        val front = side(Side.FRONT)
        val back = side(Side.BACK)
        if (back.isEmpty()) front else "$front | $back"
    }.getOrNull()
}
