package com.tracel.plugin.command.action.support

import com.tracel.engine.log.LookupFilter
import com.tracel.model.event.EventKind
import com.tracel.model.holder.HolderId
import com.tracel.model.transaction.Transaction
import com.tracel.model.world.WorldChange
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.support.LookupSearch.Companion.WINDOW
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.presenter.Actors
import com.tracel.plugin.command.presenter.ChangeLinePresenter
import com.tracel.plugin.command.presenter.LookupPresenter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lookup, read as the pages ask for it. */
internal class LookupSearch(
    private val services: TracelServices,
    private val filter: LookupFilter,
    val parsed: ParsedLookupArgs,
    blocks: Boolean,
    items: Boolean,
    val flushed: Boolean,
    events: Set<EventKind> = emptySet(),
) {
    /** What the sender sees of the page asked for. */
    class View(val page: Int, val rows: List<ChangeLinePresenter.Stack>, val total: Int)

    val complete: Boolean get() = (world?.done ?: true) && (txns?.done ?: true) && (said?.done ?: true)

    private val lock = Mutex()
    private val stacks = ArrayList<ChangeLinePresenter.Stack>()
    private val byKey = HashMap<Any, ChangeLinePresenter.Stack>()
    private val halves = HashMap<Pair<Any, Long>, ChangeLinePresenter.Stack>()
    private val visitNewest = HashMap<Long, Long>()
    private val actors = Actors(services.actors)
    private val rolled = HashMap<Long, Long>()

    private val world = if (blocks) Cursor({ until, limit ->
        services.reading { services.worldLog.query(filter.copy(until = until, limit = limit)) }
            .also { changes ->
                actors.learn(changes.map { it.causedBy })
                learnRolled(changes.map { it.seq.raw })
            }
    }, { it.epochMillis }, { it.seq.raw }, filter.until) else null

    private val txns = if (items) Cursor({ until, limit ->
        services.reading { services.log.query(filter.copy(until = until, limit = limit)) }
            .also { found ->
                actors.learn(found.flatMap(::holdersOf))
                learnRolled(found.map { it.seq.raw })
            }
    }, { it.epochMillis }, { it.seq.raw }, filter.until) else null

    private val said = if (events.isNotEmpty()) Cursor({ until, limit ->
        services.reading { services.events.query(filter.copy(until = until, limit = limit), events) }
            .also { found -> actors.learn(found.map { it.by }) }
    }, { it.epochMillis }, { it.seq.raw }, filter.until) else null

    /** Page [asked], with the whole search read first. */
    suspend fun page(asked: Int): View = lock.withLock {
        drain()
        val total = maxOf(1, pagesOf(stacks.size))
        val page = if (asked > total) 1 else asked
        View(page, ordered().drop((page - 1) * PAGE).take(PAGE), total)
    }

    /** Every line of the search, in the order the pages show them. */
    suspend fun all(): List<ChangeLinePresenter.Stack> = lock.withLock {
        drain()
        ordered()
    }

    private fun ordered(): List<ChangeLinePresenter.Stack> = stacks.sortedWith(
        compareByDescending<ChangeLinePresenter.Stack> { it.group }.thenComparator { a, b ->
            if (a.first.visit != null && a.first.visit == b.first.visit) a.oldest.compareTo(b.oldest) else 0
        },
    )

    private suspend fun learnRolled(seqs: List<Long>) {
        rolled += services.rolledBack.of(seqs)
    }

    private fun holdersOf(transaction: Transaction): List<HolderId?> =
        listOf(transaction.causedBy) + transaction.flows.flatMap { listOf(it.source, it.destination) }

    private fun pagesOf(rows: Int) = (rows + PAGE - 1) / PAGE

    private suspend fun drain() {
        while (!complete) pull()
    }

    private suspend fun pull() {
        val a = world?.head()
        val b = txns?.head()
        val c = said?.head()
        val newest = listOfNotNull(
            a?.let { Triple(it.epochMillis, it.seq.raw, 0) },
            b?.let { Triple(it.epochMillis, it.seq.raw, 1) },
            c?.let { Triple(it.epochMillis, it.seq.raw, 2) },
        ).maxWithOrNull(compareBy<Triple<Long, Long, Int>> { it.first }.thenBy { it.second }
            .thenByDescending { it.third }) ?: return
        when (newest.third) {
            0 -> world!!.drop()?.let(::add)
            1 -> txns!!.drop()?.let(::add)
            else -> said!!.drop()?.let { stack(ChangeLinePresenter.logged(it, actors)) }
        }
    }

    private fun add(change: WorldChange) {
        if (parsed.actions.isEmpty() && !parsed.natural && ChangeLinePresenter.isUnnamedChange(change)) return
        stack(ChangeLinePresenter.logged(change, actors, rolled))
    }

    private fun add(transaction: Transaction) {
        ChangeLinePresenter.logged(transaction, parsed.item, parsed.natural, actors, rolled).forEach(::stack)
    }

    private fun stack(line: ChangeLinePresenter.Logged) {
        if (parsed.each) {
            val waiting = if (line.pairs) halves.remove(line.key to line.millis) else null
            if (waiting != null && waiting.first.counted != line.counted) {
                waiting.add(line)
                return
            }
            val own = ChangeLinePresenter.Stack(line)
            if (line.pairs) halves[line.key to line.millis] = own
            stacks += own
            return
        }
        val existing = byKey[line.key]
        if (existing != null && (line.visit != null || existing.oldest - line.millis <= GAP_MILLIS)) existing.add(line)
        else ChangeLinePresenter.Stack(line).also {
            line.visit?.let { visit -> it.group = visitNewest.getOrPut(visit) { line.millis } }
            byKey[line.key] = it
            stacks += it
        }
    }

    /**
     * A log read backwards in time, [WINDOW] records at a time.
     *
     * Each window ends at the oldest time seen, and what was already taken at that very millisecond is skipped;
     * a window that brings nothing new but was full gets wider, so a thousand edits in one tick cannot stall it.
     */
    private class Cursor<T>(
        private val fetch: suspend (until: Long?, limit: Int) -> List<T>,
        private val millis: (T) -> Long,
        private val seq: (T) -> Long,
        private var until: Long?,
    ) {
        private val buffer = ArrayDeque<T>()
        private val seen = HashSet<Long>()
        private var first = true
        private var window = WINDOW
        var done = false
            private set

        suspend fun head(): T? {
            while (buffer.isEmpty() && !done) refill()
            return buffer.firstOrNull()
        }

        fun drop(): T? = buffer.removeFirstOrNull()

        private suspend fun refill() {
            val got = fetch(until, window)
            val fresh = if (first) got else got.filter { seq(it) !in seen }
            first = false
            if (got.isEmpty()) {
                done = true
                return
            }
            if (fresh.isEmpty()) {
                if (window >= WIDEST) done = true else window *= 4
                return
            }
            buffer += fresh
            val oldest = got.minOf(millis)
            if (oldest != until) seen.clear()
            until = oldest
            for (item in got) if (millis(item) == oldest) seen += seq(item)
            window = WINDOW
        }
    }

    companion object {
        const val PAGE: Int = LookupPresenter.LOOKUP_PAGE

        private const val GAP_MILLIS = 60_000L

        private const val WINDOW = 1000
        private const val WIDEST = 1 shl 20
    }
}
