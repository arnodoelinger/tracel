package com.tracel.plugin.api.addon

import com.tracel.api.addon.ActorKind
import com.tracel.api.addon.BlockLens
import com.tracel.api.addon.CustomEventType
import com.tracel.api.addon.ItemHolderType
import com.tracel.api.addon.ItemLens
import com.tracel.api.addon.FlagInput
import com.tracel.api.addon.QueryFlag
import com.tracel.api.addon.QueryPart
import com.tracel.api.result.Res
import com.tracel.api.world.NamespacedName
import net.kyori.adventure.text.Component
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack

internal class GuardedHolder<I : Any>(private val inner: ItemHolderType<I>, private val guard: AddonGuard) :
    ItemHolderType<I> by inner {
    override fun encode(id: I): String = guard.now("holder ${inner.name} encode") { inner.encode(id) }

    override fun decode(text: String): I? = guard.now("holder ${inner.name} decode") { inner.decode(text) }

    override fun read(id: I): List<ItemStack?>? = guard.now("holder ${inner.name} read") { inner.read(id) }

    override fun write(id: I, slot: Int, item: ItemStack?) =
        guard.now("holder ${inner.name} write") { inner.write(id, slot, item) }
}

internal class GuardedBlockLens(private val inner: BlockLens, private val guard: AddonGuard) : BlockLens {
    override fun name(data: BlockData): NamespacedName? = guard.now("block lens") { inner.name(data) }

    override fun data(name: NamespacedName): BlockData? = guard.now("block lens") { inner.data(name) }
}

internal class GuardedItemLens(private val inner: ItemLens, private val guard: AddonGuard) : ItemLens {
    override fun name(item: ItemStack): NamespacedName? = guard.now("item lens") { inner.name(item) }
}

internal class GuardedEvent<T : Any>(private val inner: CustomEventType<T>, private val guard: AddonGuard) :
    CustomEventType<T> by inner {
    override fun encode(value: T): String = guard.now("event ${inner.name} encode") { inner.encode(value) }

    override fun decode(version: Int, text: String): T? =
        guard.now("event ${inner.name} decode") { inner.decode(version, text) }

    override fun line(value: T): Component = guard.now("event ${inner.name} line") { inner.line(value) }
}

internal class GuardedActorKind(private val inner: ActorKind, private val guard: AddonGuard) : ActorKind by inner {
    override fun display(id: String): String? = guard.now("actor ${inner.name}") { inner.display(id) }
}

internal class GuardedFlag(private val inner: QueryFlag, private val guard: AddonGuard, val addon: String) :
    QueryFlag by inner {
    override fun parse(input: FlagInput): Res<QueryPart, String> =
        guard.now("flag $name parse") { inner.parse(input) }

    override fun suggest(input: FlagInput): List<String> = guard.now("flag $name suggest") { inner.suggest(input) }
}
