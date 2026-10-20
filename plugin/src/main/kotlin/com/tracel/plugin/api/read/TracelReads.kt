package com.tracel.plugin.api.read

import com.tracel.api.actor.Actor
import com.tracel.api.history.ActorFacts
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
import com.tracel.plugin.adapter.item.toItemKey
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant

private const val WATCH_EVERY_MILLIS = 1_000L
private const val WATCH_LATE_MILLIS = 5_000L

/** [HistoryReads] over the world, transaction and event logs. */
internal class TracelReads(
    private val services: TracelServices,
    private val rows: HistoryRows = HistoryRows(services),
    private val open: () -> Boolean = { true },
) : HistoryReads {
    override fun history(query: HistoryQuery): History = read(query)

    override fun actorFacts(): ActorFacts = Actors(services.actors)

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

    override suspend fun settle(): Boolean = services.flushCapture()

    override fun watch(build: HistoryQuery.Builder.() -> Unit): Flow<HistoryRecord> {
        val query = HistoryQuery().with(build)
        val from = maxOf(System.currentTimeMillis(), query.since?.toEpochMilli() ?: 0L)
        return flow {
            val seen = HashSet<HistoryRecord>()
            while (open()) {
                delay(WATCH_EVERY_MILLIS)
                val since = maxOf(from, System.currentTimeMillis() - WATCH_LATE_MILLIS)
                val recent = ArrayList<HistoryRecord>()
                val history = read(query.copy(since = Instant.ofEpochMilli(since)))
                var cursor: HistoryCursor? = null
                do {
                    val page = history.page(cursor).getOrElse { throw TracelException(it) }
                    recent += page.records
                    cursor = page.next
                } while (cursor != null)
                for (record in recent.asReversed()) if (seen.add(record)) emit(record)
                seen.removeAll { it.at.toEpochMilli() < since }
                query.until?.let { if (since > it.toEpochMilli()) return@flow }
            }
        }
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

    private suspend fun learnItemNames(rows: List<Row>) {
        val keys = rows.filterIsInstance<Row.Txn>().flatMapTo(HashSet()) { row -> row.transaction.flows.map { it.itemKey } }
        if (keys.isNotEmpty()) Extensions.live.learnItemNames(keys) { services.itemForms.find(it) }
    }

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
            val page = rows.page(query, from, size).getOrElse { return Res.Err(it) }
            learnItemNames(page.rows)
            val item = query.item?.let { page.filter.material }
            val records = page.rows.flatMap { row ->
                when (row) {
                    is Row.World -> listOfNotNull(ApiConversions.record(row.change, query, page.rolled))
                    is Row.Txn -> ApiConversions.records(row.transaction, query, item, page.rolled)
                    is Row.Event -> listOf(ApiConversions.record(row.event))
                }
            }
            return Res.Ok(HistoryPage(records, page.next))
        }
    }
}
