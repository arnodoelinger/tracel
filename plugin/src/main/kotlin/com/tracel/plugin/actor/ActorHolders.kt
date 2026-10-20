package com.tracel.plugin.actor

import com.tracel.api.actor.Actor
import com.tracel.api.history.HistoryReadError
import com.tracel.api.item.ItemHolder
import com.tracel.api.item.SourceKind
import com.tracel.api.result.Res
import com.tracel.api.write.WriteError
import com.tracel.model.cause.CauseKind as ModelCause
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind as ModelSink
import com.tracel.model.holder.SourceKind as ModelSource
import com.tracel.model.rollback.RollbackJobId
import com.tracel.plugin.api.read.ApiConversions

/**
 * The one place who did it, and who holds what, turns from what the log keeps ([HolderId]) into what the API says
 * ([Actor], [ItemHolder]) and back. Two places doing it is how they start to disagree.
 */
internal object ActorHolders {
    private const val TRACEL = "Tracel"

    /** Who did it, as the API says it; `null` for what nobody did, [Actor.Natural] for the world's own doings. */
    fun actor(holder: HolderId?, cause: ModelCause): Actor? = when (holder) {
        null -> if (cause.isNatural) Actor.Natural else null
        is HolderId.Player -> Actor.Player(holder.uuid)
        is HolderId.PlayerStash -> Actor.Player(holder.uuid)
        is HolderId.Entity -> Actor.Entity(holder.uuid)
        is HolderId.PlacedEntity -> Actor.Entity(holder.uuid)
        is HolderId.ItemEntity -> Actor.Entity(holder.uuid)
        is HolderId.AtBlock -> Actor.Block(ApiConversions.place(holder.pos))
        is HolderId.Escrow -> Actor.Plugin(TRACEL)
        is HolderId.Plugin -> Actor.Plugin(holder.name)
        is HolderId.Custom -> Actor.Addon(ApiConversions.kind(holder.kind), holder.id)
        is HolderId.Source, is HolderId.Sink -> if (cause.isNatural) Actor.Natural else null
    }

    /** The holders a lookup for [actor] reads, or why it cannot. */
    fun holders(actor: Actor): Res<Set<HolderId>, HistoryReadError> = when (actor) {
        is Actor.Player -> Res.Ok(setOf(HolderId.Player(actor.uuid)))
        is Actor.Entity -> Res.Ok(
            setOf(HolderId.Entity(actor.uuid), HolderId.PlacedEntity(actor.uuid), HolderId.ItemEntity(actor.uuid)),
        )
        is Actor.Block -> ApiConversions.pos(actor.place).let { Res.Ok(setOf(HolderId.Block(it), HolderId.PlacedBlock(it))) }
        Actor.Natural -> Res.Err(
            HistoryReadError.Unsupported("Natural is nobody; ask for natural() and its causes instead."),
        )
        is Actor.Plugin -> Res.Ok(setOf(HolderId.Plugin(actor.name)))
        is Actor.Addon -> Res.Ok(setOf(HolderId.Custom(actor.kind.toString(), actor.id)))
    }

    /** Where items sat, as the API says it. */
    fun holder(id: HolderId): ItemHolder = when (id) {
        is HolderId.Block -> ItemHolder.Container(ApiConversions.place(id.pos))
        is HolderId.PlacedBlock -> ItemHolder.PlacedBlock(ApiConversions.place(id.pos))
        is HolderId.Player -> ItemHolder.Player(id.uuid)
        is HolderId.PlayerStash -> ItemHolder.Player(id.uuid, stash = true)
        is HolderId.Entity -> ItemHolder.Entity(id.uuid)
        is HolderId.PlacedEntity -> ItemHolder.PlacedEntity(id.uuid)
        is HolderId.ItemEntity -> ItemHolder.Dropped(id.uuid)
        is HolderId.Escrow -> ItemHolder.Rollback
        is HolderId.Plugin -> ItemHolder.Appeared(SourceKind.PLUGIN)
        is HolderId.Source -> ItemHolder.Appeared(ApiConversions.source(id.kind))
        is HolderId.Sink -> ItemHolder.Gone(ApiConversions.sink(id.kind))
        is HolderId.Custom -> ItemHolder.Custom(ApiConversions.kind(id.kind), id.id)
    }

    /** The holder an [ItemHolder] stands for, so that what names holders can name it. */
    fun holderId(holder: ItemHolder): HolderId = when (holder) {
        is ItemHolder.Container -> HolderId.Block(ApiConversions.pos(holder.place))
        is ItemHolder.PlacedBlock -> HolderId.PlacedBlock(ApiConversions.pos(holder.place))
        is ItemHolder.Player -> if (holder.stash) HolderId.PlayerStash(holder.uuid) else HolderId.Player(holder.uuid)
        is ItemHolder.Entity -> HolderId.Entity(holder.uuid)
        is ItemHolder.PlacedEntity -> HolderId.PlacedEntity(holder.uuid)
        is ItemHolder.Dropped -> HolderId.ItemEntity(holder.uuid)
        ItemHolder.Rollback -> HolderId.Escrow(RollbackJobId(0))
        is ItemHolder.Appeared -> HolderId.Source(ModelSource.valueOf(holder.kind.name))
        is ItemHolder.Gone -> HolderId.Sink(ModelSink.valueOf(holder.kind.name))
        is ItemHolder.Custom -> HolderId.Custom(holder.kind.toString(), holder.id)
    }


    /**
     * Whose doing a write is: [by], or [signer] itself when [by] is `null`. A plugin writes in its own name, never in
     * another's, and the world is nobody. A `null` [signer] is the host's own addons, which may sign for any plugin.
     */
    fun writer(by: Actor?, signer: String?): Res<HolderId, WriteError> = when (by) {
        null ->
            if (signer != null) Res.Ok(HolderId.Plugin(signer))
            else Res.Err(WriteError.Unsupported("Somebody has to have done it."))
        is Actor.Plugin ->
            if (signer == null || by.name == signer) Res.Ok(HolderId.Plugin(by.name))
            else Res.Err(WriteError.Unsupported("A plugin writes in its own name, not as ${by.name}."))
        is Actor.Player -> Res.Ok(HolderId.Player(by.uuid))
        is Actor.Entity -> Res.Ok(HolderId.Entity(by.uuid))
        is Actor.Block -> Res.Ok(HolderId.Block(ApiConversions.pos(by.place)))
        Actor.Natural -> Res.Err(WriteError.Unsupported("Natural is nobody; a plugin's change is the plugin's."))
        is Actor.Addon -> Res.Ok(HolderId.Custom(by.kind.toString(), by.id))
    }
}
