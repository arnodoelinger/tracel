package com.tracel.plugin.bukkit.block

import com.tracel.annotations.Unstable
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.Banner
import org.bukkit.block.BlockState
import org.bukkit.block.Sign
import org.bukkit.block.Skull
import org.bukkit.block.sign.Side
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BannerMeta
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.SkullMeta

/**
 * Banners.
 *
 * @see Banner
 * @see BannerMeta
 */
@Unstable
internal object BannerExtras {
    fun of(state: BlockState): BlockExtras? {
        if (state !is Banner) return null
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? BannerMeta ?: return null
        meta.patterns = state.patterns
        state.customName()?.let { meta.customName(it) }
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}

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

/**
 * Heads. Their item is a [SkullMeta], not a block-state carrier, so the generic path kept nothing
 * and a textured head came back as Steve.
 */
@Unstable
@Suppress("DEPRECATION")
internal object SkullExtras {
    fun of(state: BlockState): BlockExtras? {
        if (state !is Skull) return null
        val carrier = ItemStack(PlacementItem.of(state))
        val meta = carrier.itemMeta as? SkullMeta ?: return null
        meta.ownerProfile = state.ownerProfile
        meta.noteBlockSound = state.noteBlockSound
        carrier.itemMeta = meta
        return BlockExtras.Opaque(carrier.serializeAsBytes())
    }
}
