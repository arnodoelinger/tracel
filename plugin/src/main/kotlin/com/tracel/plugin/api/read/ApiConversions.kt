package com.tracel.plugin.api.read

import com.tracel.api.actor.Actor
import com.tracel.api.history.ActionKind
import com.tracel.api.history.BlockRecord
import com.tracel.api.history.CauseKind
import com.tracel.api.history.EntityRecord
import com.tracel.api.history.EventKind
import com.tracel.api.history.EventRecord
import com.tracel.api.history.FlowKind
import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.HistoryReadError
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.history.RecordKind
import com.tracel.api.item.ItemHolder
import com.tracel.api.item.ItemKind
import com.tracel.api.item.SinkKind
import com.tracel.api.item.SourceKind
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import com.tracel.api.world.NamespacedName
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.model.event.ActorEvent
import com.tracel.model.flow.FlowKind as ModelFlow
import com.tracel.model.world.entity.leashHolder
import com.tracel.plugin.adapter.block.special.SignText
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.rollback.RollbackJobId
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.TILE_SHIFT
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.WorldId
import java.time.Instant
import java.util.UUID
import com.tracel.model.cause.CauseKind as ModelCause
import com.tracel.model.event.EventKind as ModelEvent
import com.tracel.model.holder.SinkKind as ModelSink
import com.tracel.model.holder.SourceKind as ModelSource
import com.tracel.model.world.ActionKind as ModelAction

/** Item name for the ledger and the block IDs it stands as, the way `MaterialAliases` answers. */
internal typealias MaterialResolver = (String) -> Pair<String, Set<String>>

/**
 * The one place engine types turn into API types and back.
 *
 * Nothing else in the plugin converts a [HolderId] to an [Actor];
 * two places doing it is how they start to disagree.
 */
internal object ApiConversions {
    private const val TRACEL = "Tracel"
    private val ANY_HEIGHT = Int.MIN_VALUE to Int.MAX_VALUE

    fun cause(kind: ModelCause): CauseKind = CauseKind.valueOf(kind.name)

    fun cause(kind: CauseKind): ModelCause = ModelCause.valueOf(kind.name)

    fun action(kind: ModelAction): ActionKind = ActionKind.valueOf(kind.name)

    fun action(kind: ActionKind): ModelAction = ModelAction.valueOf(kind.name)

    fun event(kind: ModelEvent): EventKind = EventKind.valueOf(kind.name)

    fun event(kind: EventKind): ModelEvent = ModelEvent.valueOf(kind.name)

    fun source(kind: ModelSource): SourceKind = SourceKind.valueOf(kind.name)

    fun sink(kind: ModelSink): SinkKind = SinkKind.valueOf(kind.name)

    /** What a sign says: only asked of a sign edit, because reading it deserializes the tile. */
    private fun signText(action: ModelAction, shape: BlockShape): String? =
        if (action == ModelAction.SIGN_EDIT) SignText.of(shape) else null

    fun flowKind(kind: ModelFlow): FlowKind = FlowKind.valueOf(kind.name)

    fun place(pos: BlockPos): BlockPlace = BlockPlace(pos.world.uuid, pos.x, pos.y, pos.z)

    fun pos(place: BlockPlace): BlockPos = BlockPos(WorldId(place.world), place.x, place.y, place.z)

    fun item(key: ItemKey): ItemKind = ItemKind(key.material, key.decoration?.hex)

    fun actor(holder: HolderId?, cause: ModelCause): Actor? = when (holder) {
        null -> if (cause.isNatural) Actor.Natural else null
        is HolderId.Player -> Actor.Player(holder.uuid)
        is HolderId.PlayerStash -> Actor.Player(holder.uuid)
        is HolderId.Entity -> Actor.Entity(holder.uuid)
        is HolderId.PlacedEntity -> Actor.Entity(holder.uuid)
        is HolderId.ItemEntity -> Actor.Entity(holder.uuid)
        is HolderId.AtBlock -> Actor.Block(place(holder.pos))
        is HolderId.Escrow -> Actor.Plugin(TRACEL)
        is HolderId.Plugin -> Actor.Plugin(holder.name)
        is HolderId.Custom -> Actor.Addon(kind(holder.kind), holder.id)
        is HolderId.Source, is HolderId.Sink -> if (cause.isNatural) Actor.Natural else null
    }

    fun holders(actor: Actor): Res<Set<HolderId>, HistoryReadError> = when (actor) {
        is Actor.Player -> Res.Ok(setOf(HolderId.Player(actor.uuid)))
        is Actor.Entity -> Res.Ok(
            setOf(HolderId.Entity(actor.uuid), HolderId.PlacedEntity(actor.uuid), HolderId.ItemEntity(actor.uuid)),
        )
        is Actor.Block -> pos(actor.place).let { Res.Ok(setOf(HolderId.Block(it), HolderId.PlacedBlock(it))) }
        Actor.Natural -> Res.Err(
            HistoryReadError.Unsupported("Natural is nobody; ask for natural() and its causes instead."),
        )
        is Actor.Plugin -> Res.Ok(setOf(HolderId.Plugin(actor.name)))
        is Actor.Addon -> Res.Ok(setOf(HolderId.Custom(actor.kind.toString(), actor.id)))
    }

    fun holder(id: HolderId): ItemHolder = when (id) {
        is HolderId.Block -> ItemHolder.Container(place(id.pos))
        is HolderId.PlacedBlock -> ItemHolder.PlacedBlock(place(id.pos))
        is HolderId.Player -> ItemHolder.Player(id.uuid)
        is HolderId.PlayerStash -> ItemHolder.Player(id.uuid, stash = true)
        is HolderId.Entity -> ItemHolder.Entity(id.uuid)
        is HolderId.PlacedEntity -> ItemHolder.PlacedEntity(id.uuid)
        is HolderId.ItemEntity -> ItemHolder.Dropped(id.uuid)
        is HolderId.Escrow -> ItemHolder.Rollback
        is HolderId.Plugin -> ItemHolder.Appeared(SourceKind.PLUGIN)
        is HolderId.Source -> ItemHolder.Appeared(source(id.kind))
        is HolderId.Sink -> ItemHolder.Gone(sink(id.kind))
        is HolderId.Custom -> ItemHolder.Custom(kind(id.kind), id.id)
    }

    fun holderId(holder: ItemHolder): HolderId = when (holder) {
        is ItemHolder.Container -> HolderId.Block(pos(holder.place))
        is ItemHolder.PlacedBlock -> HolderId.PlacedBlock(pos(holder.place))
        is ItemHolder.Player -> if (holder.stash) HolderId.PlayerStash(holder.uuid) else HolderId.Player(holder.uuid)
        is ItemHolder.Entity -> HolderId.Entity(holder.uuid)
        is ItemHolder.PlacedEntity -> HolderId.PlacedEntity(holder.uuid)
        is ItemHolder.Dropped -> HolderId.ItemEntity(holder.uuid)
        ItemHolder.Rollback -> HolderId.Escrow(RollbackJobId(0))
        is ItemHolder.Appeared -> HolderId.Source(ModelSource.valueOf(holder.kind.name))
        is ItemHolder.Gone -> HolderId.Sink(ModelSink.valueOf(holder.kind.name))
        is ItemHolder.Custom -> HolderId.Custom(holder.kind.toString(), holder.id)
    }

    fun kind(stored: String): NamespacedName = NamespacedName.parse(stored) ?: NamespacedName("unknown", "unknown")

    fun region(area: LookupArea): LookupRegion? = when (area) {
        is LookupArea.Around -> {
            val r = area.radius
            val (minY, maxY) = if (area.anyHeight) ANY_HEIGHT else (area.center.y - r) to (area.center.y + r)
            box(
                area.world,
                area.center.x - r, minY, area.center.z - r,
                area.center.x + r, maxY, area.center.z + r,
            )
        }
        is LookupArea.Cube -> {
            val (minY, maxY) = if (area.anyHeight) ANY_HEIGHT else area.minY to area.maxY
            box(area.world, area.minX, minY, area.minZ, area.maxX, maxY, area.maxZ)
        }
        is LookupArea.Chunks -> {
            val x = area.center.x shr TILE_SHIFT
            val z = area.center.z shr TILE_SHIFT
            LookupRegion(
                WorldId(area.world),
                x - area.radius, x + area.radius, z - area.radius, z + area.radius,
            )
        }
        is LookupArea.World -> null
    }

    fun filter(query: HistoryQuery, materials: MaterialResolver): Res<LookupFilter, HistoryReadError> {
        val natural = Actor.Natural in query.by
        if (natural && query.by.size > 1) {
            return Res.Err(HistoryReadError.Unsupported("by(Natural) cannot be mixed with other actors."))
        }
        val holders = HashSet<HolderId>()
        for (actor in query.by - Actor.Natural) {
            when (val got = holders(actor)) {
                is Res.Ok -> holders += got.value
                is Res.Err -> return got
            }
        }
        val excluded = HashSet<HolderId>()
        for (actor in query.notBy - Actor.Natural) {
            when (val got = holders(actor)) {
                is Res.Ok -> excluded += got.value
                is Res.Err -> return got
            }
        }
        val causes = query.causes.mapTo(HashSet(), ::cause).let { named ->
            if (natural) named + (ModelCause.NATURAL_CAUSES - query.notCauses.map(::cause).toSet()) else named
        }
        val hidden = if (query.natural || natural) emptySet() else ModelCause.NATURAL_CAUSES - causes
        val resolved = query.item?.let { materials(it.material) }
        val area = query.area
        return Res.Ok(
            LookupFilter(
                holders = holders,
                excludedHolders = excluded,
                material = resolved?.first,
                blockMaterials = resolved?.second.orEmpty(),
                causes = causes,
                worldCauses = query.blockCauses?.mapTo(HashSet(), ::cause),
                excludedCauses = query.notCauses.mapTo(HashSet(), ::cause) + hidden,
                actions = query.actions.mapTo(HashSet(), ::action),
                since = query.since?.toEpochMilli(),
                until = query.until?.toEpochMilli(),
                region = area?.let(::region),
                world = area?.let { WorldId(it.world) },
                entity = query.entity,
                limit = Int.MAX_VALUE,
            ),
        )
    }

    fun rollbackFilter(query: HistoryQuery, materials: MaterialResolver): Res<LookupFilter, HistoryReadError> =
        filter(query.copy(natural = true), materials)

    fun events(query: HistoryQuery): Set<ModelEvent> = when {
        RecordKind.EVENT !in query.kinds -> emptySet()
        query.events.isEmpty() -> ModelEvent.entries.toSet()
        else -> query.events.mapTo(HashSet(), ::event)
    }

    fun cursor(millis: Long, seq: Long): HistoryCursor = HistoryCursor(millis, seq)

    fun record(change: WorldChange, query: HistoryQuery, rolled: Map<Long, Long>): HistoryRecord? {
        val at = Instant.ofEpochMilli(change.epochMillis)
        val actor = actor(change.causedBy, change.cause)
        val cursor = cursor(change.epochMillis, change.seq.raw)
        val rolledBack = instant(rolled[change.seq.raw])
        return when (val subject = change.subject) {
            is ChangeSubject.Block -> if (RecordKind.BLOCK !in query.kinds) null else BlockRecord(
                at, actor, cause(change.cause), rolledBack, place(change.at), cursor,
                action(change.action), subject.before.data.value, subject.after.data.value,
                signText(change.action, subject.before), signText(change.action, subject.after),
            )
            is ChangeSubject.Entity -> if (RecordKind.ENTITY !in query.kinds) null else EntityRecord(
                at, actor, cause(change.cause), rolledBack, place(change.at), cursor,
                action(change.action), subject.entity, subject.type.value,
                existedBefore = subject.before != null, existsAfter = subject.after != null,
                leashedBefore = subject.before?.extras.leashHolder != null,
                leashedAfter = subject.after?.extras.leashHolder != null,
            )
        }
    }

    fun records(transaction: Transaction, query: HistoryQuery, item: String?, rolled: Map<Long, Long>): List<ItemFlow> {
        val at = Instant.ofEpochMilli(transaction.epochMillis)
        val actor = actor(transaction.causedBy, transaction.cause)
        val cursor = cursor(transaction.epochMillis, transaction.seq.raw)
        val rolledBack = instant(rolled[transaction.seq.raw])
        val place = transaction.at?.let(::place)
        val wanted = query.item
        return transaction.flows.mapNotNull { flow ->
            val kind = item(flow.itemKey)
            if (item != null && !kind.material.equals(item, ignoreCase = true)) return@mapNotNull null
            if (wanted != null && query.exactItem && kind.variant != wanted.variant) return@mapNotNull null
            ItemFlow(
                at, actor, cause(transaction.cause), rolledBack, place, cursor,
                kind, flow.quantity.raw, holder(flow.source), holder(flow.destination), flowKind(flow.kind),
            )
        }
    }

    fun record(event: ActorEvent): EventRecord {
        val actor = actor(event.by, ModelCause.PLAYER_ACTION)
        val cause = when (actor) {
            is Actor.Player -> CauseKind.PLAYER_ACTION
            is Actor.Plugin -> CauseKind.PLUGIN
            else -> CauseKind.UNKNOWN
        }
        val custom = event.kind == ModelEvent.CUSTOM
        val raw = event.text
        val name = if (custom) raw?.substringBefore(' ')?.let(NamespacedName::parse) else null
        val text = if (custom) raw?.substringAfter(' ', "")?.ifEmpty { null } else raw
        return EventRecord(
            Instant.ofEpochMilli(event.epochMillis), actor, cause, null, event.at?.let(::place),
            cursor(event.epochMillis, event.seq.raw), event(event.kind), text, event.entity, name,
        )
    }

    fun customText(name: NamespacedName, text: String?): String = if (text.isNullOrEmpty()) "$name" else "$name $text"

    private fun box(world: UUID, minX: Int, minY: Int, minZ: Int, maxX: Int, maxY: Int, maxZ: Int) =
        LookupRegion(
            WorldId(world),
            minX shr TILE_SHIFT, maxX shr TILE_SHIFT, minZ shr TILE_SHIFT, maxZ shr TILE_SHIFT,
            minX, maxX, minY, maxY, minZ, maxZ,
        )

    private fun instant(millis: Long?): Instant? = millis?.let(Instant::ofEpochMilli)
}
