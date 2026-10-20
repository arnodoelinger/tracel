package com.tracel.plugin.api.addon

import com.tracel.api.actor.Actor
import com.tracel.api.addon.Reversibles
import com.tracel.api.addon.Reversible
import com.tracel.api.access.TracelConnection
import com.tracel.api.access.Grant
import com.tracel.api.access.AccessError
import com.tracel.api.addon.ActorKind
import com.tracel.api.addon.ActorKinds
import com.tracel.api.addon.AddonContext
import com.tracel.api.addon.AddonRecorder
import com.tracel.api.addon.BlockLens
import com.tracel.api.addon.CustomEventType
import com.tracel.api.addon.EventTypes
import com.tracel.api.addon.HolderTypes
import com.tracel.api.addon.ItemHolderType
import com.tracel.api.addon.ItemLens
import com.tracel.api.addon.Lenses
import com.tracel.api.addon.RegisteredActorKind
import com.tracel.api.addon.RegisteredEvent
import com.tracel.api.addon.RegisteredHolder
import com.tracel.api.history.EventKind
import com.tracel.api.history.EventRecord
import com.tracel.api.item.ItemHolder
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.NamespacedName
import com.tracel.api.write.WriteError
import com.tracel.plugin.api.Owned

/** What an addon's registries need from a running `Tracel`: writing history down. */
internal interface AddonWrites {
    /** The recorder for addon [addon]; batches it hands out are owned by [owned]. */
    fun recorder(addon: String, owned: Owned): AddonRecorder

    /**
     * Finishes what the addon behind reversible event [kind] was not told yet,
     * now that it is here.
     */
    fun reconcile(kind: String) {}

    /** Writes a custom event [name] with [text], as [by]'s doing or [addon]'s own. */
    suspend fun record(
        addon: String,
        name: NamespacedName,
        text: String,
        by: Actor?,
        at: BlockPlace?,
    ): Res<Unit, WriteError>

    companion object {
        val NONE: AddonWrites = object : AddonWrites {
            override fun recorder(addon: String, owned: Owned): AddonRecorder = error("No recorder here.")

            override suspend fun record(
                addon: String,
                name: NamespacedName,
                text: String,
                by: Actor?,
                at: BlockPlace?,
            ): Res<Unit, WriteError> = Res.Err(WriteError.Unsupported("Nothing is written here."))
        }
    }
}

/**
 * One enabled addon's [AddonContext].
 *
 * Whatever it registers goes into [extensions] and comes out of them again when
 * [owned] closes.
 */
internal class AddonScope(
    override val id: String,
    val owned: Owned,
    private val extensions: Extensions,
    private val writes: AddonWrites,
    val guard: AddonGuard,
    override val connection: TracelConnection,
) : AddonContext {
    override fun <T : AutoCloseable> own(resource: T): T = owned.own(resource)

    override val holders: HolderTypes = object : HolderTypes {
        override fun <I : Any> register(type: ItemHolderType<I>): RegisteredHolder<I> {
            val name = ours(type.name, "holder type")
            owned.own(extensions.holders.put(name.toString(), GuardedHolder(type, guard)))
            return Holder(type, name)
        }
    }

    override val lenses: Lenses = object : Lenses {
        override fun block(lens: BlockLens) {
            owned.own(extensions.lenses.add(GuardedBlockLens(lens, guard)))
        }

        override fun item(lens: ItemLens) {
            owned.own(extensions.itemLenses.add(GuardedItemLens(lens, guard)))
        }
    }

    override val eventTypes: EventTypes = object : EventTypes {
        override fun <T : Any> register(type: CustomEventType<T>): RegisteredEvent<T> {
            val name = ours(type.name, "event type")
            require(type.version >= 1) { "Event type $name must have a version of 1 or more, not ${type.version}." }
            val guarded = GuardedEvent(type, guard)
            owned.own(extensions.events.put(name.toString(), guarded))
            return Event(guarded, name, reversible = false)
        }
    }

    override val actorKinds: ActorKinds = object : ActorKinds {
        override fun register(kind: ActorKind): RegisteredActorKind {
            val name = ours(kind.name, "actor kind")
            owned.own(extensions.actors.put(name.toString(), GuardedActorKind(kind, guard)))
            return object : RegisteredActorKind {
                override val kind: ActorKind = kind

                override fun get(id: String): Actor.Addon = Actor.Addon(name, id)
            }
        }
    }

    private val recording by lazy { writes.recorder(id, owned) }

    override val recorder: Res<AddonRecorder, AccessError> get() = granted(Grant.ATTRIBUTE) { recording }

    override val reversibles: Res<Reversibles, AccessError> get() = granted(Grant.ROLLBACK) { reversing }

    private val reversing = object : Reversibles {
        override fun <T : Any> register(type: CustomEventType<T>, how: Reversible<T>): RegisteredEvent<T> {
            val name = ours(type.name, "event type")
            require(type.version >= 1) { "Event type $name must have a version of 1 or more, not ${type.version}." }
            val guarded = GuardedEvent(type, guard)
            owned.own(extensions.events.put(name.toString(), guarded))
            owned.own(extensions.reversibles.put(name.toString(), BoundReversible(guard) { undone, step, text ->
                val (version, payload) = EventText.split(text) ?: return@BoundReversible false
                val value = type.decode(version, payload) ?: return@BoundReversible false
                if (undone) how.undo(step, value) else how.redo(step, value)
                true
            }))
            writes.reconcile(name.toString())
            return Event(guarded, name, reversible = true)
        }
    }

    private inline fun <T> granted(grant: Grant, capability: () -> T): Res<T, AccessError> = when {
        !connection.isOpen -> Res.Err(AccessError.Revoked)
        grant !in connection.grants -> Res.Err(AccessError.NotGranted(grant))
        else -> Res.Ok(capability())
    }

    private fun ours(name: NamespacedName, what: String): NamespacedName {
        require(name.namespace == id) { "Addon \"$id\" can only register a $what in its own namespace, not $name." }
        return name
    }

    private class Holder<I : Any>(override val type: ItemHolderType<I>, private val name: NamespacedName) :
        RegisteredHolder<I> {
        override fun get(id: I): ItemHolder.Custom = ItemHolder.Custom(name, type.encode(id))

        override fun idOf(holder: ItemHolder.Custom): I? = if (holder.kind != name) null else type.decode(holder.id)
    }

    private inner class Event<T : Any>(
        override val type: CustomEventType<T>,
        private val name: NamespacedName,
        private val reversible: Boolean,
    ) : RegisteredEvent<T> {
        override suspend fun record(value: T, by: Actor?, at: BlockPlace?): Res<Unit, WriteError> {
            if (!connection.isOpen) return Res.Err(WriteError.Revoked)
            if (Grant.ATTRIBUTE !in connection.grants) return Res.Err(WriteError.NotGranted(Grant.ATTRIBUTE))
            return writes.record(id, name, EventText.join(type.version, type.encode(value), reversible), by, at)
        }

        override fun read(record: EventRecord): T? {
            if (record.kind != EventKind.CUSTOM || record.name != name) return null
            val (version, payload) = EventText.split(record.text.orEmpty()) ?: return null
            return runCatching { type.decode(version, payload) }.getOrNull()
        }
    }
}
