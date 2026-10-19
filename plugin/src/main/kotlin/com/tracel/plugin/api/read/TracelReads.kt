package com.tracel.plugin.api.read

import com.tracel.api.actor.Actor
import com.tracel.api.history.ContainerTaker
import com.tracel.api.history.History
import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.HistoryPage
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.HistoryReadError
import com.tracel.api.history.HistoryReads
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.history.RecordKind
import com.tracel.api.item.ItemHolder
import com.tracel.api.item.ItemKind
import com.tracel.api.result.Res
import com.tracel.api.result.TracelException
import com.tracel.api.result.andThen
import com.tracel.api.result.getOrElse
import com.tracel.api.world.LookupArea
import com.tracel.api.world.place
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.ActorEvent
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.WorldChange
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.command.args.support.MaterialAliases
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** [HistoryReads] over the world, transaction and event logs. */
internal class TracelReads(
    private val services: TracelServices,
    private val materials: MaterialResolver = MaterialAliases::resolve,
) : HistoryReads {
    override fun Block.history(build: HistoryQuery.Builder.() -> Unit): History =
        read(HistoryQuery(area = LookupArea.of(place)).with(build))

    override suspend fun Block.lastTouchedBy(): Res<Actor, HistoryReadError> =
        first(read(HistoryQuery(area = LookupArea.of(place), kinds = setOf(RecordKind.BLOCK)))) { it.actor != null }
            .andThen { found -> found?.actor?.let { Res.Ok(it) } ?: Res.Err(HistoryReadError.NotFound) }

    override fun Player.history(build: HistoryQuery.Builder.() -> Unit): History =
        read(HistoryQuery(by = setOf(Actor.Player(uniqueId))).with(build))

    override fun LookupArea.history(build: HistoryQuery.Builder.() -> Unit): History =
        read(HistoryQuery(area = this).with(build))

    override fun ItemStack.kind(): ItemKind = ApiConversions.item(toItemKey())

    override suspend fun ItemStack.origin(): Res<ItemFlow, HistoryReadError> =
        first(trail()) { it is ItemFlow && it.from is ItemHolder.Appeared }
            .andThen { found -> (found as? ItemFlow)?.let { Res.Ok(it) } ?: Res.Err(HistoryReadError.NotFound) }

    override fun ItemStack.trail(build: HistoryQuery.Builder.() -> Unit): History =
        read(HistoryQuery(kinds = setOf(RecordKind.ITEM), item = kind(), exactItem = true).with(build))

    override suspend fun Block.whoTook(
        kind: ItemKind,
        build: HistoryQuery.Builder.() -> Unit,
    ): Res<List<ContainerTaker>, HistoryReadError> {
        val chest = ItemHolder.Container(place)
        val history = read(
            HistoryQuery(
                area = LookupArea.of(chest.place),
                kinds = setOf(RecordKind.ITEM),
                item = kind,
                exactItem = kind.variant != null,
            ).with(build),
        )
        val took = LinkedHashMap<Actor?, ContainerTaker>()
        var from: HistoryCursor? = null
        do {
            val page = history.page(from).getOrElse { return Res.Err(it) }
            for (flow in page.records) {
                if (flow !is ItemFlow || flow.from != chest || flow.to == chest) continue
                val was = took[flow.actor]
                val amount = (was?.amount ?: 0L) + flow.amount
                took[flow.actor] = ContainerTaker(flow.actor, amount, maxOf(was?.last ?: flow.at, flow.at))
            }
            from = page.next
        } while (from != null)
        return Res.Ok(took.values.sortedByDescending { it.amount })
    }

    private suspend fun first(
        history: History,
        wanted: (HistoryRecord) -> Boolean,
    ): Res<HistoryRecord?, HistoryReadError> {
        var from: HistoryCursor? = null
        do {
            val page = history.page(from).getOrElse { return Res.Err(it) }
            page.records.firstOrNull(wanted)?.let { return Res.Ok(it) }
            from = page.next
        } while (from != null)
        return Res.Ok(null)
    }

    private fun read(query: HistoryQuery): History = LogHistory(query)

    private inner class LogHistory(override val query: HistoryQuery) : History {
        override val records: Flow<HistoryRecord>
            get() = flow {
                var from: HistoryCursor? = null
                do {
                    val page = page(from).getOrElse { throw TracelException(it) }
                    for (record in page.records) emit(record)
                    from = page.next
                } while (from != null)
            }

        override suspend fun page(from: HistoryCursor?, size: Int): Res<HistoryPage, HistoryReadError> {
            if (size <= 0) return Res.Err(HistoryReadError.Unsupported("A page holds at least one record, not $size."))
            val filter = ApiConversions.filter(query, materials).getOrElse { return Res.Err(it) }
            return try {
                Res.Ok(read(filter, from, size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Res.Err(HistoryReadError.Failed(failure.message ?: failure.javaClass.simpleName))
            }
        }

        private suspend fun read(filter: LookupFilter, from: HistoryCursor?, size: Int): HistoryPage {
            val rows = CursorPager.page(sources(filter), from, filter.until, size)
            val rolled = services.rolledBack.of(rows.rows.mapNotNull(Row::rollable))
            val item = query.item?.let { filter.material }
            val records = rows.rows.flatMap { row ->
                when (row) {
                    is Row.World -> listOfNotNull(ApiConversions.record(row.change, query, rolled))
                    is Row.Txn -> ApiConversions.records(row.transaction, query, item, rolled)
                    is Row.Event -> listOf(ApiConversions.record(row.event))
                }
            }
            return HistoryPage(records, rows.next)
        }

        private fun sources(filter: LookupFilter): List<LogSource<Row>> {
            val sources = ArrayList<LogSource<Row>>(3)
            if (RecordKind.BLOCK in query.kinds || RecordKind.ENTITY in query.kinds) {
                sources += LogSource({ until, limit ->
                    services.reading { services.worldLog.query(filter.copy(until = until, limit = limit)) }
                        .map(Row::World)
                }, Row::cursor)
            }
            if (RecordKind.ITEM in query.kinds) {
                sources += LogSource({ until, limit ->
                    services.reading { services.log.query(filter.copy(until = until, limit = limit)) }.map(Row::Txn)
                }, Row::cursor)
            }
            val events = ApiConversions.events(query)
            if (events.isNotEmpty()) {
                // An event has no cause, so thhe natural causes hidden by default must not hide it
                val said = filter.copy(excludedCauses = emptySet(), worldCauses = null)
                sources += LogSource({ until, limit ->
                    services.reading { services.events.query(said.copy(until = until, limit = limit), events) }
                        .map(Row::Event)
                }, Row::cursor)
            }
            return sources
        }
    }

    private sealed interface Row {
        val cursor: HistoryCursor
        val rollable: Long?

        class World(val change: WorldChange) : Row {
            override val cursor = ApiConversions.cursor(change.epochMillis, change.seq.raw)
            override val rollable: Long get() = change.seq.raw
        }

        class Txn(val transaction: Transaction) : Row {
            override val cursor = ApiConversions.cursor(transaction.epochMillis, transaction.seq.raw)
            override val rollable: Long get() = transaction.seq.raw
        }

        class Event(val event: ActorEvent) : Row {
            override val cursor = ApiConversions.cursor(event.epochMillis, event.seq.raw)
            override val rollable: Long? get() = null
        }
    }
}
