package com.tracel.plugin.api.read

import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.HistoryReadError
import com.tracel.api.history.RecordKind
import com.tracel.api.result.Res
import com.tracel.api.result.getOrElse
import com.tracel.engine.log.lookup.LookupFilter
import com.tracel.model.event.ActorEvent
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.WorldChange
import com.tracel.plugin.api.addon.withItemLens
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException

/** One entry of a log, before it is a record: what the lookup lines are made of, and the API's records too. */
internal sealed interface Row {
    val cursor: HistoryCursor

    /** The sequence number a rollback marks, or `null` for what is never rolled back. */
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

/** [rows] newest first, [next] where the page after them starts, and when a rollback took each back, by sequence. */
internal class RowPage(
    val rows: List<Row>,
    val next: HistoryCursor?,
    val rolled: Map<Long, Long>,
    val filter: LookupFilter,
)

/**
 * The three logs read as one history, the way every [HistoryQuery] is answered: the API's records are made of these
 * rows, and so are the lines `/tracel lookup` shows. One reader, so the two never disagree about what a query finds.
 */
internal class HistoryRows(
    private val services: TracelServices,
    private val materials: MaterialResolver = MaterialAliases::resolve,
) {
    /** What [queries] find together, at least [size] rows older than [from], newest first. */
    suspend fun page(
        queries: List<HistoryQuery>,
        from: HistoryCursor?,
        size: Int,
    ): Res<RowPage, HistoryReadError> {
        if (size <= 0) return Res.Err(HistoryReadError.Unsupported("A page holds at least one record, not $size."))
        val sources = ArrayList<LogSource<Row>>(3 * queries.size)
        var first: LookupFilter? = null
        for (query in queries) {
            val filter = ApiConversions.filter(query, materials).getOrElse { return Res.Err(it) }
                .let { services.withItemLens(it) }
            if (first == null) first = filter
            sources += sources(query, filter)
        }
        return try {
            val rows = CursorPager.page(sources, from, size)
            val rolled = services.rolledBack.of(rows.rows.mapNotNull(Row::rollable))
            Res.Ok(RowPage(rows.rows, rows.next, rolled, first ?: LookupFilter()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Telemetry.error(failure)
            Res.Err(HistoryReadError.Failed(failure.message ?: failure.javaClass.simpleName))
        }
    }

    /** What [query] finds, [size] rows older than [from]. */
    suspend fun page(query: HistoryQuery, from: HistoryCursor?, size: Int): Res<RowPage, HistoryReadError> =
        page(listOf(query), from, size)

    /** Every row [queries] find, newest first, read [PAGE] at a time. */
    suspend fun all(queries: List<HistoryQuery>): Res<List<Row>, HistoryReadError> {
        val found = ArrayList<Row>()
        var from: HistoryCursor? = null
        do {
            val got = page(queries, from, PAGE).getOrElse { return Res.Err(it) }
            found += got.rows
            from = got.next
        } while (from != null)
        return Res.Ok(found)
    }

    private fun sources(query: HistoryQuery, filter: LookupFilter): List<LogSource<Row>> {
        val until = filter.until
        val sources = ArrayList<LogSource<Row>>(3)
        if (RecordKind.BLOCK in query.kinds || RecordKind.ENTITY in query.kinds) {
            sources += LogSource({ at, limit ->
                services.reading { services.worldLog.query(filter.copy(until = at, limit = limit)) }.map(Row::World)
            }, Row::cursor, until)
        }
        if (RecordKind.ITEM in query.kinds) {
            sources += LogSource({ at, limit ->
                services.reading { services.log.query(filter.copy(until = at, limit = limit)) }.map(Row::Txn)
            }, Row::cursor, until)
        }
        val events = ApiConversions.events(query)
        if (events.isNotEmpty()) {
            // An event has no cause, so the natural causes hidden by default must not hide it
            val said = filter.copy(excludedCauses = emptySet(), worldCauses = null)
            sources += LogSource({ at, limit ->
                services.reading { services.events.query(said.copy(until = at, limit = limit), events) }
                    .map(Row::Event)
            }, Row::cursor, until)
        }
        return sources
    }

    private companion object {
        const val PAGE = 2_000
    }
}
