package com.tracel.plugin.api.addon

import com.tracel.api.actor.Actor
import com.tracel.api.addon.ActorKind
import com.tracel.api.addon.Addon
import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.BlockLens
import com.tracel.api.addon.CustomEventType
import com.tracel.api.addon.ItemHolderType
import com.tracel.api.addon.RegisteredEvent
import com.tracel.api.addon.RegisteredHolder
import com.tracel.api.addon.TracelAddon
import com.tracel.api.history.CauseKind
import com.tracel.api.history.EventKind
import com.tracel.api.history.EventRecord
import com.tracel.api.history.HistoryCursor
import com.tracel.api.result.Res
import com.tracel.api.world.NamespacedName
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.plugin.actor.ActorHolders
import com.tracel.plugin.rollback.material.preflightAddons
import com.tracel.plugin.rollback.result.outcome.PreflightResult
import com.tracel.plugin.rollback.result.outcome.Unreachable
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.block.data.BlockData
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddonRegistriesTest {
    private val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }
    private val extensions = Extensions(logger)

    private fun manager() = AddonManager(logger, isPluginEnabled = { true }, extensions = extensions)

    private fun plain(component: Component) = PlainTextComponentSerializer.plainText().serialize(component)

    object Backpacks : ItemHolderType<Int> {
        override val name = NamespacedName("backpacks", "backpack")
        override fun encode(id: Int) = id.toString()
        override fun decode(text: String) = text.toIntOrNull()
        override fun read(id: Int): List<ItemStack?> = emptyList()
        override fun write(id: Int, slot: Int, item: ItemStack?) = Unit
    }

    object Opened : CustomEventType<String> {
        override val name = NamespacedName("backpacks", "opened")
        override val version = 2
        override fun encode(value: String) = value
        override fun decode(version: Int, text: String) = if (version == 1) "old $text" else text
        override fun line(value: String): Component = Component.text("opened $value")
    }

    object Couriers : ActorKind {
        override val name = NamespacedName("backpacks", "courier")
        override fun display(id: String) = "Courier $id"
    }

    object Lens : BlockLens {
        override fun name(data: BlockData): NamespacedName? = null
        override fun data(name: NamespacedName): BlockData? = null
    }

    @Addon(id = "backpacks")
    class Registers : TracelAddon {
        lateinit var holder: RegisteredHolder<Int>
        lateinit var event: RegisteredEvent<String>

        override fun onEnable(context: AddonContext) {
            holder = context.holders.register(Backpacks)
            event = context.eventTypes.register(Opened)
            context.actorKinds.register(Couriers)
            context.lenses.block(Lens)
        }
    }

    @Test
    fun `everything an addon registered is gone once it is disabled`() {
        val manager = manager()
        manager.register(Registers())
        manager.start()
        assertTrue("backpacks:backpack" in extensions.holders)
        assertTrue("backpacks:opened" in extensions.events)
        assertTrue("backpacks:courier" in extensions.actors)
        assertEquals(1, extensions.lenses.all.size)

        manager.stop()
        assertNull(extensions.holders["backpacks:backpack"])
        assertNull(extensions.events["backpacks:opened"])
        assertNull(extensions.actors["backpacks:courier"])
        assertTrue(extensions.lenses.all.isEmpty())

        manager.start()
        assertTrue("backpacks:backpack" in extensions.holders)
    }

    @Addon(id = "squatter")
    class Squatter : TracelAddon {
        override fun onEnable(context: AddonContext) {
            context.actorKinds.register(object : ActorKind {
                override val name = NamespacedName("squatter", "ghost")
                override fun display(id: String) = id
            })
            context.holders.register(Backpacks)
        }
    }

    @Test
    fun `a name outside the addon's namespace fails its enable and takes back what it registered first`() {
        val manager = manager()
        manager.register(Squatter())
        manager.start()

        assertTrue(manager.enabledIds.isEmpty())
        assertNull(extensions.actors["squatter:ghost"])
        assertNull(extensions.holders["backpacks:backpack"])
    }

    @Test
    fun `a holder and an event read back what they wrote`() {
        val manager = manager()
        val addon = Registers()
        manager.register(addon)
        manager.start()

        val holder = addon.holder[42]
        assertEquals(NamespacedName("backpacks", "backpack"), holder.kind)
        assertEquals(42, addon.holder.idOf(holder))

        fun stored(text: String) = EventRecord(
            Instant.EPOCH, null, CauseKind.UNKNOWN, null, null, HistoryCursor(0, 1), EventKind.CUSTOM, text, null,
            NamespacedName("backpacks", "opened"),
        )
        assertEquals("red", addon.event.read(stored(EventText.join(2, "red"))))
        assertEquals("old red", addon.event.read(stored(EventText.join(1, "red"))))
        assertNull(addon.event.read(stored("red")))
    }

    @Test
    fun `records of a removed addon show as unknown, and come back once it returns`() {
        val manager = manager()
        manager.register(Registers())
        val courier = HolderId.Custom("backpacks:courier", "7")
        val pack = HolderId.Custom("backpacks:backpack", "42")
        val event = "backpacks:opened ${EventText.join(2, "red")}"

        manager.start()
        assertEquals("Courier 7", extensions.name(courier))
        assertEquals("Backpack 42", extensions.name(pack))
        assertEquals("opened red", plain(extensions.eventLine(event)))

        manager.stop()
        assertEquals("unknown backpacks:courier", extensions.name(courier))
        assertEquals("unknown backpacks:backpack", extensions.name(pack))
        assertEquals("unknown backpacks:opened", plain(extensions.eventLine(event)))
    }

    @Test
    fun `a rollback reaching into a removed addon's container is refused before anything moves`() {
        val manager = manager()
        manager.register(Registers())
        val pack = HolderId.Custom("backpacks:backpack", "42")
        val deltas = mapOf<HolderId, Map<ItemKey, Long>>(
            HolderId.Player(UUID(1, 1)) to mapOf(ItemKey("DIAMOND") to 3L),
            pack to mapOf(ItemKey("DIAMOND") to -3L),
        )
        val known = { holder: HolderId.Custom -> holder.kind in extensions.holders }

        manager.start()
        assertEquals(PreflightResult.Ok, preflightAddons(deltas, known))

        manager.stop()
        val refused = preflightAddons(deltas, known)
        assertTrue(refused is Unreachable && refused.holder == pack, refused.toString())
    }

    @Test
    fun `an addon actor is written down as a custom holder`() {
        val kind = NamespacedName("backpacks", "courier")
        val written = ActorHolders.writer(Actor.Addon(kind, "7"), signer = null)
        assertEquals(HolderId.Custom("backpacks:courier", "7"), (written as Res.Ok).value)
    }
}
