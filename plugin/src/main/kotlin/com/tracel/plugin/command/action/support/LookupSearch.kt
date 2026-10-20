package com.tracel.plugin.command.action.support

import com.tracel.api.history.BlockRecord
import com.tracel.api.history.EntityRecord
import com.tracel.api.history.EventRecord
import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.HistoryReadError
import com.tracel.api.history.HistoryReads
import com.tracel.api.history.HistoryRecord
import com.tracel.api.history.ItemFlow
import com.tracel.api.result.Res
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.line.ChangeLinePresenter
import com.tracel.plugin.command.presenter.line.LineStack
import com.tracel.plugin.command.presenter.line.LoggedLine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lookup, read as the pages ask for it. */
internal class LookupSearch(
    private val reads: HistoryReads,
    private val queries: List<HistoryQuery>,
    val parsed: ParsedLookupArgs,
    val flushed: Boolean,
) {
    /** What the sender sees of the page asked for. */
    class View(val page: Int, val rows: List<LineStack>, val total: Int)

    private val lock = Mutex()
    private val stacks = ArrayList<LineStack>()
    private val byKey = HashMap<Any, LineStack>()
    private val halves = HashMap<Pair<Any, Long>, LineStack>()
    private val visitNewest = HashMap<Long, Long>()
    private val facts = reads.actorFacts()
    private var read = false

    /** Page [asked], with the whole search read first. */
    suspend fun page(asked: Int): Res<View, HistoryReadError> = lock.withLock {
        drain()?.let { return@withLock Res.Err(it) }
        val total = maxOf(1, pagesOf(stacks.size))
        val page = if (asked > total) 1 else asked
        Res.Ok(View(page, ordered().drop((page - 1) * PAGE).take(PAGE), total))
    }

    /** Every line of the search, in the order the pages show them. */
    suspend fun all(): Res<List<LineStack>, HistoryReadError> = lock.withLock {
        drain()?.let { return@withLock Res.Err(it) }
        Res.Ok(ordered())
    }

    private fun ordered(): List<LineStack> = stacks.sortedWith(
        compareByDescending<LineStack> { it.group }.thenComparator { a, b ->
            if (a.first.visit != null && a.first.visit == b.first.visit) a.oldest.compareTo(b.oldest) else 0
        },
    )

    private fun pagesOf(rows: Int) = (rows + PAGE - 1) / PAGE

    private suspend fun drain(): HistoryReadError? {
        if (read) return null
        val found = ArrayList<HistoryRecord>()
        for (query in queries) {
            var from: HistoryCursor? = null
            do {
                val page = when (val got = reads.history(query).page(from, WINDOW)) {
                    is Res.Ok -> got.value
                    is Res.Err -> return got.error
                }
                found += page.records
                from = page.next
            } while (from != null)
        }
        found.sortByDescending { it.cursor }
        facts.learn(found)
        var at = 0
        while (at < found.size) {
            when (val record = found[at]) {
                is BlockRecord -> add(record)
                is EntityRecord -> add(record)
                is EventRecord -> stack(ChangeLinePresenter.logged(record, facts))
                is ItemFlow -> {
                    var end = at + 1
                    while (end < found.size && found[end].let { it is ItemFlow && it.cursor == record.cursor }) end++
                    add(found.subList(at, end).map { it as ItemFlow })
                    at = end - 1
                }
            }
            at++
        }
        read = true
        return null
    }

    private fun add(record: BlockRecord) {
        if (parsed.actions.isEmpty() && !parsed.natural && !parsed.turnings && ChangeLinePresenter.isUnnamedChange(record)) {
            return
        }
        stack(ChangeLinePresenter.logged(record, facts))
    }

    private fun add(record: EntityRecord) {
        if (ChangeLinePresenter.isKeptOnly(record)) return
        stack(ChangeLinePresenter.logged(record, facts))
    }

    private fun add(flows: List<ItemFlow>) {
        ChangeLinePresenter.logged(flows, parsed.item, parsed.natural, facts).forEach(::stack)
    }

    private fun stack(line: LoggedLine) {
        if (parsed.each) {
            val waiting = if (line.pairs) halves.remove(line.key to line.millis) else null
            if (waiting != null && waiting.first.counted != line.counted) {
                waiting.add(line)
                return
            }
            val own = LineStack(line)
            if (line.pairs) halves[line.key to line.millis] = own
            stacks += own
            return
        }
        val existing = byKey[line.key]
        if (existing != null && (line.visit != null || existing.oldest - line.millis <= GAP_MILLIS)) existing.add(line)
        else LineStack(line).also {
            line.visit?.let { visit -> it.group = visitNewest.getOrPut(visit) { line.millis } }
            byKey[line.key] = it
            stacks += it
        }
    }

    companion object {
        const val PAGE: Int = LookupPresenter.LOOKUP_PAGE

        private const val GAP_MILLIS = 60_000L
        private const val WINDOW = 1000
    }
}
