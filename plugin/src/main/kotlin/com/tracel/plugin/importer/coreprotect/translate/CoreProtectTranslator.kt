package com.tracel.plugin.importer.coreprotect.translate

import com.tracel.annotations.Unstable
import com.tracel.engine.foreign.ForeignRecord
import com.tracel.engine.world.edit.BlockEdit
import com.tracel.model.cause.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import com.tracel.model.item.ItemKey
import com.tracel.model.item.Quantity
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldId
import com.tracel.model.world.block.BlockDataKey
import com.tracel.model.world.block.BlockExtras
import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityExtras
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import com.tracel.plugin.importer.coreprotect.codes.BlockAction.BREAK
import com.tracel.plugin.importer.coreprotect.codes.BlockAction.CLICK
import com.tracel.plugin.importer.coreprotect.codes.BlockAction.KILL
import com.tracel.plugin.importer.coreprotect.codes.BlockAction.PLACE
import com.tracel.plugin.importer.coreprotect.codes.BlockAction.PLAYER_KILLED
import com.tracel.plugin.importer.coreprotect.codes.ContainerAction.ADDED
import com.tracel.plugin.importer.coreprotect.codes.ContainerAction.REMOVED
import com.tracel.plugin.importer.coreprotect.codes.EXPLOSIVE
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_ADD_ENDER
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_BREAK
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_BUY
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_CREATE
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_DESTROY
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_DROP
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_PICKUP
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_REMOVE_ENDER
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_SELL
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_SHOOT
import com.tracel.plugin.importer.coreprotect.codes.ItemAction.ITEM_THROW
import com.tracel.plugin.importer.coreprotect.codes.MACHINES
import com.tracel.plugin.importer.coreprotect.codes.MOB_ALIASES
import com.tracel.plugin.importer.coreprotect.codes.SessionAction.LOGIN
import com.tracel.plugin.importer.coreprotect.codes.SignAction.SIGN_BEFORE
import com.tracel.plugin.importer.coreprotect.codes.SignAction.SIGN_BREAK
import com.tracel.plugin.importer.coreprotect.codes.SignGlow.GLOW_BACK
import com.tracel.plugin.importer.coreprotect.codes.SignGlow.GLOW_BOTH
import com.tracel.plugin.importer.coreprotect.codes.SignGlow.GLOW_FRONT
import com.tracel.plugin.importer.coreprotect.source.*
import com.tracel.plugin.importer.coreprotect.tally.ImportTally
import com.tracel.plugin.importer.coreprotect.tally.Skipped
import com.tracel.plugin.importer.coreprotect.tally.Taken
import com.tracel.plugin.specifics.block.AIR
import java.util.*

/**
 * Turns `CoreProtect` rows into this store's records.
 *
 * `CoreProtect` writes one side of a change. Either the block that was broken, or the block that was placed. We keep both,
 * so the other side is worked out from what the rows before said stood there, and is air when nothing said anything.
 * The same memory is how a sign gets its text: `co_sign` says what a sign read, `co_block` says there was a sign.
 *
 * Containers and items become transactions with no lots behind them. Like everything that imported they are there to be
 * looked up: a rollback leaves imported history alone.
 *
 * @param source the database's fingerprint, so an entity from it always gets the same UUID
 * @param ownSince epoch millis from which rows are left out as [Skipped.OVERLAP]
 * @param skull looks up a row of `co_skull`
 * @param entity looks up what `co_entity` kept of a killed mob
 */
@Unstable
class CoreProtectTranslator(
    private val tables: CoreProtectTables,
    private val platform: ImportPlatform,
    private val source: Long,
    private val ownSince: Long,
    private val skull: (Int) -> SkullRow? = { null },
    private val entity: (Int) -> ByteArray? = { null },
) {
    val tally = ImportTally()

    private class Actor(val cause: CauseKind, val by: HolderId?)

    private val worlds = HashMap<Int, WorldId?>()
    private val actors = HashMap<Int, Actor>()
    private val shapes = HashMap<Int, HashMap<String, Any>>()
    private val entityTypes = HashMap<Int, EntityTypeKey?>()
    private val plainItems = HashMap<Int, Any>()
    private val standing = HashMap<Int, Standing>()
    private val mobs = LinkedHashMap<UUID, EntityTypeKey>()
    private val water = platform.blockState("minecraft:water")?.let { BlockShape(BlockDataKey(it)) } ?: AIR

    /** The mob actors met since the last call, for whoever has to tell the store what kind each one is. */
    fun newMobs(): Map<UUID, EntityTypeKey> = LinkedHashMap(mobs).also { mobs.clear() }

    /** [rows] as records, in the order they came. */
    fun translate(rows: List<SourceRow>): List<ForeignRecord> {
        val out = ArrayList<ForeignRecord>()
        var group: EditGroup? = null
        fun flush() {
            group?.let { out += ForeignRecord.Blocks(it.edits()) }
            group = null
        }
        for (row in rows) {
            tally.rows++
            val millis = row.time * MILLIS
            if (millis >= ownSince) {
                tally.skip(Skipped.OVERLAP)
                continue
            }
            val world = worldOf(row.world)
            val actor = actors.getOrPut(row.user) { actorOf(tables.users[row.user]) }
            if (row is TextRow || row is SessionRow) {
                flush()
                event(row, actor, world, millis)?.let { out += it }
                continue
            }
            if (world == null) {
                tally.skip(Skipped.WORLD)
                continue
            }
            val at = BlockPos(world, row.x, row.y, row.z)
            when (row) {
                is BlockRow -> when (row.action) {
                    BREAK, PLACE -> {
                        val edit = edit(row, at) ?: continue
                        val action = if (row.action == BREAK) ActionKind.BLOCK_BREAK else ActionKind.BLOCK_PLACE
                        val open = group
                        if (open == null || !open.takes(action, actor.cause, actor.by, millis, world, at)) {
                            flush()
                            group = EditGroup(action, actor.cause, actor.by, millis, world).also { it.add(edit) }
                        } else {
                            open.add(edit)
                        }
                        tally.took(Taken.BLOCKS, millis)
                        carried(row, actor, at, millis)?.let {
                            flush()
                            out += it
                        }
                    }

                    else -> {
                        flush()
                        single(row, actor, at, millis)?.let { out += it }
                    }
                }

                is SignRow -> {
                    flush()
                    sign(row, actor, at, millis)?.let { out += it }
                }

                is ItemRow -> {
                    flush()
                    moved(row, actor, at, millis)?.let { out += it }
                }

                else -> tally.skip(Skipped.UNREADABLE)
            }
        }
        flush()
        return out
    }

    // region Blocks

    private fun edit(row: BlockRow, at: BlockPos): BlockEdit? {
        val shape = shapeOf(row)?.let { detailed(it, row) }
        if (shape == null) {
            tally.skip(Skipped.BLOCK)
            return null
        }
        val standing = standing.getOrPut(row.world) { Standing() }
        val key = Standing.key(row.x, row.y, row.z)
        val stood = standing[key]

        // CoreProtect writes the sign down again when its text is edited: the same sign, not a new one
        if (row.action == PLACE && stood?.data == shape.data && shape.data.value.substringBefore('[').endsWith(SIGN)) {
            tally.folded++
            return null
        }
        val edit = if (row.action == BREAK) {
            // What stood there may know more than the row does: a sign's text comes from another table
            val before = stood?.takeIf { it.data == shape.data && it.extras != null && shape.extras == null } ?: shape
            BlockEdit(at, before, if (WATERLOGGED in shape.data.value) water else AIR)
        } else {
            // Placed over the very thing it is: something was there that nobody wrote down
            BlockEdit(at, stood?.takeIf { it != shape } ?: AIR, shape)
        }
        if (edit.before == edit.after) {
            tally.skip(Skipped.UNREADABLE)
            return null
        }
        // What CoreProtect itself rolled back is history, but it is not what stands there
        if (!row.rolledBack) standing[key] = edit.after
        return edit
    }

    private fun single(row: BlockRow, actor: Actor, at: BlockPos, millis: Long): ForeignRecord? = when (row.action) {
        // A player killed is written as a kill of type nothing, with the player where the mob's data would be
        KILL if row.type == PLAYER_KILLED -> {
            val victim = tables.users[row.data]?.takeIf { !it.name.startsWith("#") && it.name.isNotEmpty() }
            if (victim == null) {
                tally.skip(Skipped.UNREADABLE)
                null
            } else {
                tally.took(Taken.DEATHS, millis)
                val killer = tables.users[row.user]?.name.orEmpty().removePrefix("#").replace('_', ' ')
                ForeignRecord.Happened(
                    EventKind.DEATH,
                    HolderId.Player(victim.uuid ?: platform.player(victim.name)),
                    millis,
                    at,
                    killer
                )
            }
        }

        KILL -> {
            val type = entityTypes.getOrPut(row.type) {
                tables.entities[row.type]?.let(platform::entityType)?.let(::EntityTypeKey)
            }
            if (type == null) {
                tally.skip(Skipped.ENTITY)
                null
            } else {
                val kept = row.data.takeIf { it > 0 }?.let(entity)?.let(platform::decode)
                // Newer rows say which mob it was; for the rest the row itself has to do
                val uuid =
                    (kept?.getOrNull(KEPT_UUID) as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: UUID.nameUUIDFromBytes("tracel:coreprotect:entity:$source:${row.rowId}".toByteArray())
                val snapshot = kept?.let {
                    platform.entitySnapshot(
                        at.world,
                        type.value,
                        row.x + CENTER,
                        row.y.toDouble(),
                        row.z + CENTER,
                        it
                    )
                }
                val before = EntityShape(
                    type, row.x + CENTER, row.y.toDouble(), row.z + CENTER,
                    extras = snapshot?.let(EntityExtras::Opaque),
                )
                tally.took(Taken.ENTITIES, millis)
                ForeignRecord.Change(
                    ActionKind.ENTITY_REMOVE,
                    actor.cause,
                    actor.by,
                    millis,
                    at,
                    ChangeSubject.Entity(uuid, type, before, null),
                )
            }
        }

        CLICK -> {
            val shape = shapeOf(row)
            if (shape == null) {
                tally.skip(Skipped.BLOCK)
                null
            } else {
                tally.took(Taken.CLICKS, millis)
                ForeignRecord.Change(
                    ActionKind.BLOCK_CLICK,
                    actor.cause,
                    actor.by,
                    millis,
                    at,
                    ChangeSubject.Block(shape, shape)
                )
            }
        }

        else -> {
            tally.skip(Skipped.UNREADABLE)
            null
        }
    }

    private fun sign(row: SignRow, actor: Actor, at: BlockPos, millis: Long): ForeignRecord? {
        val standing = standing.getOrPut(row.world) { Standing() }
        val key = Standing.key(row.x, row.y, row.z)
        val stood = standing[key]
        // The table says what a sign read and not which sign: without its block row there is nothing to hang it on
        if (stood == null || !stood.data.value.substringBefore('[').endsWith(SIGN)) {
            tally.skip(Skipped.UNREADABLE)
            return null
        }
        val detail = BlockDetail.Sign(
            row.lines, row.color, row.colorSecondary,
            glowing = row.glowing == GLOW_FRONT || row.glowing == GLOW_BOTH,
            glowingBack = row.glowing == GLOW_BACK || row.glowing == GLOW_BOTH,
            waxed = row.waxed,
        )
        val extras = platform.blockExtras(stood.data.value, detail)
        if (extras == null) {
            tally.skip(Skipped.UNREADABLE)
            return null
        }
        val reads = stood.copy(extras = BlockExtras.Opaque(extras))
        standing[key] = reads
        val blank = stood.extras == null && row.lines.all(String::isEmpty)
        // What it read before an edit, or before it broke is the other half of a row still to come
        if (row.action == SIGN_BREAK || row.action == SIGN_BEFORE || blank || reads == stood) {
            tally.folded++
            return null
        }
        tally.took(Taken.SIGNS, millis)
        return ForeignRecord.Change(
            ActionKind.SIGN_EDIT,
            actor.cause,
            actor.by,
            millis,
            at,
            ChangeSubject.Block(stood, reads)
        )
    }

    private fun shapeOf(row: BlockRow): BlockShape? {
        val byData = shapes.getOrPut(row.type) { HashMap() }
        val found = byData.getOrPut(row.blockData ?: "") {
            val material = tables.materials[row.type]?.let(::namespaced) ?: return@getOrPut UNKNOWN
            val properties = row.blockData?.split(',')?.mapNotNull { part ->
                val word = part.trim()
                // A number of points into the map; an old database wrote the property out in place
                word.toIntOrNull()?.let { tables.blockData[it] } ?: word.takeIf { '=' in it }
            }.orEmpty()
            val state =
                if (properties.isEmpty()) null else platform.blockState("$material[${properties.joinToString(",")}]")
            (state ?: platform.blockState(material))?.let { BlockShape(BlockDataKey(it)) } ?: UNKNOWN
        }
        return found as? BlockShape
    }

    private fun carried(row: BlockRow, actor: Actor, at: BlockPos, millis: Long): ForeignRecord? {
        val meta = row.meta ?: return null
        if (!shapeOf(row)!!.data.value.substringBefore('[').endsWith(SHULKER)) return null
        val stacks = platform.decode(meta)?.mapNotNull(platform::stack)?.filter { it.second > 0 } ?: return null
        if (stacks.isEmpty()) return null
        val box = HolderId.Block(at.world, at.x, at.y, at.z)
        val player = actor.by as? HolderId.Player
        val placed = row.action == PLACE
        val flows = stacks.groupBy({ it.first }, { it.second.toLong() }).map { (item, counts) ->
            val quantity = Quantity(counts.sum())
            when {
                player != null && placed -> Flow(item, quantity, player, box, FlowKind.MOVE)
                player != null -> Flow(item, quantity, box, player, FlowKind.MOVE)
                placed -> Flow(item, quantity, HolderId.Source(SourceKind.UNATTRIBUTED), box, FlowKind.MINT)
                else -> Flow(item, quantity, box, HolderId.Sink(SinkKind.UNATTRIBUTED), FlowKind.BURN)
            }
        }
        return ForeignRecord.Moved(actor.cause, actor.by, millis, at, flows)
    }

    private fun detailed(shape: BlockShape, row: BlockRow): BlockShape {
        val material = shape.data.value.substringBefore('[')
        val detail: BlockDetail? = when {
            material.endsWith("command_block") ->
                row.meta?.let(platform::decode)?.firstNotNullOfOrNull { it as? String }?.let(BlockDetail::Command)

            material.endsWith("_banner") ->
                row.meta?.let(platform::decode)?.filterIsInstance<Map<*, *>>()?.takeIf { it.isNotEmpty() }
                    ?.let(BlockDetail::Banner)

            material.endsWith("_head") || material.endsWith("_skull") ->
                row.data.takeIf { it > 0 }?.let(skull)?.let { BlockDetail.Head(it.owner, it.skin) }

            material == SPAWNER -> BlockDetail.Spawner(tables.entities[row.data] ?: DEFAULT_SPAWN)
            else -> null
        }
        val extras = detail?.let { platform.blockExtras(shape.data.value, it) } ?: return shape
        return shape.copy(extras = BlockExtras.Opaque(extras))
    }

    // endregion

    // region Items

    private fun moved(row: ItemRow, actor: Actor, at: BlockPos, millis: Long): ForeignRecord? {
        val item = itemOf(row)
        if (item == null) {
            tally.skip(Skipped.ITEM)
            return null
        }
        val flow = if (row.amount <= 0) null else when (row.table) {
            SourceTable.CONTAINER -> containerFlow(row, actor, at, item)
            else -> (actor.by as? HolderId.Player)?.let { itemFlow(row, it, item) }
        }
        if (flow == null) {
            tally.skip(Skipped.UNREADABLE)
            return null
        }
        tally.took(if (row.table == SourceTable.CONTAINER) Taken.CONTAINERS else Taken.ITEMS, millis)
        val cause = if (flow.kind == FlowKind.TRANSFORM_OUT) CauseKind.CRAFT else actor.cause
        return ForeignRecord.Moved(cause, actor.by, millis, at, listOf(flow))
    }

    private fun containerFlow(row: ItemRow, actor: Actor, at: BlockPos, item: ItemKey): Flow? {
        val chest = HolderId.Block(at.world, at.x, at.y, at.z)
        val quantity = Quantity(row.amount.toLong())
        val player = actor.by as? HolderId.Player
        return when (row.action) {
            ADDED -> if (player != null) Flow(item, quantity, player, chest, FlowKind.MOVE)
            else Flow(item, quantity, HolderId.Source(SourceKind.UNATTRIBUTED), chest, FlowKind.MINT)

            REMOVED -> if (player != null) Flow(item, quantity, chest, player, FlowKind.MOVE)
            else Flow(item, quantity, chest, HolderId.Sink(SinkKind.UNATTRIBUTED), FlowKind.BURN)

            else -> null
        }
    }

    private fun itemFlow(row: ItemRow, player: HolderId.Player, item: ItemKey): Flow? {
        val quantity = Quantity(row.amount.toLong())
        fun pile() =
            HolderId.ItemEntity(UUID.nameUUIDFromBytes("tracel:coreprotect:item:$source:${row.rowId}".toByteArray()))
        return when (row.action) {
            ITEM_DROP, ITEM_THROW -> Flow(item, quantity, player, pile(), FlowKind.MOVE)
            ITEM_PICKUP -> Flow(item, quantity, pile(), player, FlowKind.MOVE)
            ITEM_REMOVE_ENDER -> Flow(item, quantity, HolderId.PlayerStash(player.uuid), player, FlowKind.MOVE)
            ITEM_ADD_ENDER -> Flow(item, quantity, player, HolderId.PlayerStash(player.uuid), FlowKind.MOVE)
            ITEM_SHOOT, ITEM_BREAK, ITEM_DESTROY, ITEM_SELL ->
                Flow(item, quantity, player, HolderId.Sink(SinkKind.UNATTRIBUTED), FlowKind.BURN)

            ITEM_CREATE -> Flow(item, quantity, HolderId.Source(SourceKind.CRAFT), player, FlowKind.TRANSFORM_OUT)
            ITEM_BUY -> Flow(item, quantity, HolderId.Source(SourceKind.UNATTRIBUTED), player, FlowKind.MINT)
            else -> null
        }
    }

    private fun itemOf(row: ItemRow): ItemKey? {
        val material = tables.materials[row.type]?.let(::namespaced) ?: return null
        val metadata = row.metadata?.let(platform::decode)?.takeIf { it.isNotEmpty() }
        if (metadata != null) return platform.itemKey(material, metadata)
        return plainItems.getOrPut(row.type) { platform.itemKey(material, null) ?: UNKNOWN } as? ItemKey
    }

    // endregion

    private fun event(row: SourceRow, actor: Actor, world: WorldId?, millis: Long): ForeignRecord? {
        val player = actor.by as? HolderId.Player
        val kind = when (row) {
            is SessionRow -> if (row.action == LOGIN) EventKind.JOIN else EventKind.QUIT
            is TextRow -> if (row.table == SourceTable.COMMAND) EventKind.COMMAND else EventKind.CHAT
            else -> null
        }
        val text = (row as? TextRow)?.message
        if (player == null || kind == null || (row is TextRow && text.isNullOrEmpty())) {
            tally.skip(Skipped.UNREADABLE)
            return null
        }
        tally.took(
            when (kind) {
                EventKind.CHAT -> Taken.CHAT
                EventKind.COMMAND -> Taken.COMMANDS
                else -> Taken.SESSIONS
            },
            millis,
        )
        // Where it was said is worth less than that it was said: a world that is gone costs the place and no more
        return ForeignRecord.Happened(kind, player, millis, world?.let { BlockPos(it, row.x, row.y, row.z) }, text)
    }

    private fun worldOf(id: Int): WorldId? = worlds.getOrPut(id) {
        val name = tables.worlds[id] ?: "#$id"
        platform.world(name).also { if (it == null) tally.missingWorlds += name }
    }

    private fun actorOf(user: CoreProtectUser?): Actor {
        val name = user?.name.orEmpty()
        if (name.isEmpty()) return Actor(CauseKind.UNKNOWN, null)
        if (!name.startsWith("#")) {
            return Actor(CauseKind.PLAYER_ACTION, HolderId.Player(user?.uuid ?: platform.player(name)))
        }
        val what = name.drop(1).lowercase()
        val mob = (MOB_ALIASES[what] ?: what).let(platform::entityType)?.let(::EntityTypeKey)
        val by = mob?.let { kind ->
            val uuid = UUID.nameUUIDFromBytes("tracel:coreprotect:actor:${kind.value}".toByteArray())
            mobs[uuid] = kind
            HolderId.Entity(uuid)
        }
        val cause = when {
            what in EXPLOSIVE -> CauseKind.EXPLOSION
            what in MACHINES -> CauseKind.MACHINE
            mob != null -> CauseKind.ENTITY_ACTION
            else -> CauseKind.WORLD
        }
        return Actor(cause, by)
    }

    private fun namespaced(material: String): String =
        material.lowercase().let { if (':' in it) it else "minecraft:$it" }

    private companion object {
        const val MILLIS = 1_000L
        const val CENTER = 0.5

        @Unstable
        const val WATERLOGGED = "waterlogged=true"

        @Unstable
        const val SIGN = "_sign"

        @Unstable
        const val SPAWNER = "minecraft:spawner"

        @Unstable
        const val SHULKER = "shulker_box"

        const val KEPT_UUID = 7

        @Unstable
        const val DEFAULT_SPAWN = "pig"

        val UNKNOWN = Any()
    }
}
