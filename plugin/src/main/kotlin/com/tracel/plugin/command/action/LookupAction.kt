package com.tracel.plugin.command.action

import com.tracel.api.history.HistoryQuery
import com.tracel.api.history.RecordKind
import com.tracel.api.result.Res
import com.tracel.api.world.BlockPlace
import com.tracel.api.world.LookupArea
import com.tracel.model.event.EventKind
import com.tracel.plugin.adapter.command.resolvePlayerUuid
import com.tracel.plugin.api.addon.Extensions
import com.tracel.plugin.api.read.ApiConversions
import com.tracel.plugin.command.action.support.LookupSearch
import com.tracel.plugin.command.action.support.describe
import com.tracel.plugin.command.args.action.ActionArgument
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.lookup.lookupQuery
import com.tracel.plugin.command.args.scope.actionProblems
import com.tracel.plugin.command.args.scope.areaOf
import com.tracel.plugin.command.args.scope.scopeProblem
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.line.ChangeLinePresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.integration.privatebin.PrivateBin
import com.tracel.plugin.metrics.Telemetry
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Action responsible for executing transaction and world log lookups. */
class LookupAction(private val services: TracelServices) {
    private val searches = ConcurrentHashMap<Any, LookupSearch>()

    private fun key(sender: CommandSender): Any = (sender as? Player)?.uniqueId ?: sender.name

    private companion object {
        const val MAX_LINES = 50_000

        val INSPECT_HIDDEN = setOf(
            EventKind.CHAT,
            EventKind.COMMAND,
            EventKind.JOIN,
            EventKind.QUIT,
        )
    }

    /** The command and the place of the sender's last search, for a refresh. */
    fun last(sender: CommandSender): Pair<String, Location?>? =
        searches[key(sender)]?.let { it.parsed.command to it.parsed.anchor }

    /** Shows [page] of the sender's last search. */
    fun turn(sender: CommandSender, page: Int) {
        val search = searches[key(sender)]
        if (search == null) sender.send("lookup.no_search") else show(sender, search, page)
    }

    /** The whole of the sender's last search, encrypted and put on the `privatebin.net` server the config names. */
    fun export(sender: CommandSender) {
        val search = searches[key(sender)] ?: return sender.send("lookup.no_search")
        sender.send("lookup.export.start")
        services.scope.launch {
            val done = runCatching {
                val stacks = when (val read = search.all()) {
                    is Res.Ok -> read.value
                    is Res.Err -> error(read.error.describe())
                }
                if (stacks.isEmpty()) return@runCatching null
                val text =
                    ChangeLinePresenter.document(stacks, search.parsed.command, System.currentTimeMillis(), MAX_LINES)
                val paste = services.paste
                PrivateBin(URI.create(paste.url), paste.expire, paste.burn).upload(text) to stacks.size
            }
            done.onSuccess { sent ->
                if (sent == null) sender.send("lookup.no_matches") else LookupPresenter.exported(
                    sender,
                    sent.first,
                    sent.second
                )
            }.onFailure {
                if (it is CancellationException) throw it
                sender.failed("lookup.export.failed", Component.text(unexpected(it)), tr("lookup.export.hint.again"))
            }
        }
    }

    /** What the inspector shows for a click on [block]: the lookup of that one block, pages and all. */
    fun inspect(sender: CommandSender, block: Block, entity: Entity? = null) {
        val place = BlockPlace.of(block)
        val parsed = ParsedLookupArgs(anchor = block.location, command = "lookup block", all = true, turnings = true)
        val shown = HistoryQuery(area = LookupArea.of(place), natural = true)
        val said = EventKind.entries.toSet() - INSPECT_HIDDEN
        val events = shown.copy(
            kinds = setOf(RecordKind.EVENT),
            events = said.mapTo(HashSet(), ApiConversions::event),
            area = if (entity == null) shown.area else LookupArea.World(place.world),
            entity = entity?.uniqueId,
        )
        runLookup(sender, listOf(shown, events), parsed, Extensions.live.inspect(place))
    }

    /** Executes lookup. */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (parsed.errors.isNotEmpty()) {
            refuse(sender, parsed.errors)
            return
        }
        Telemetry.flags(Telemetry.LOOKUP_FLAGS, parsed)

        val unresolved = parsed.users.filter { resolvePlayerUuid(it) == null }
        if (unresolved.isNotEmpty()) {
            refuse(sender, unresolved.map { tr("common.reason.unknown_player", "name" to it) })
            return
        }
        val users = parsed.users.mapNotNull { resolvePlayerUuid(it) }

        val actions = ActionArgument.parse(parsed.actions)
        val actionProblems = actionProblems(actions, parsed.actions)
        if (actionProblems.isNotEmpty()) {
            refuse(sender, actionProblems)
            return
        }

        val scope = parsed.scope
        val named = parsed.world?.let { Bukkit.getWorld(it) }
        if (parsed.world != null && named == null) {
            refuse(sender, listOf(tr("common.reason.unknown_world", "name" to parsed.world)))
            return
        }
        val center = if (scope != null) parsed.anchor ?: (sender as? Player)?.location else null
        scopeProblem(scope, center, named)?.let {
            refuse(sender, listOf(it))
            return
        }

        val area = areaOf(center, scope, parsed.horizontalOnly, named)
        val events = if (parsed.all && parsed.actions.isEmpty()) EventKind.entries.toSet() else actions.events
        val query = parsed.lookupQuery(users, actions, area, events)

        runLookup(sender, listOf(query), parsed.copy(anchor = center))
    }

    private fun show(sender: CommandSender, search: LookupSearch, page: Int, extra: List<Component> = emptyList()) {
        services.scope.launch {
            val view = when (val read = search.page(page)) {
                is Res.Ok -> read.value
                is Res.Err -> return@launch sender.failed(
                    "lookup.failed", Component.text(read.error.describe()), tr("lookup.hint.again"),
                )
            }
            LookupPresenter.render(sender, view, search)
            extra.forEach(sender::sendMessage)
        }
    }

    private fun refuse(sender: CommandSender, reasons: List<Component>) =
        sender.failed("lookup.failed", reasons.asReason(), tr("common.hint.fix_flags", "command" to "lookup"))

    private fun runLookup(
        sender: CommandSender,
        queries: List<HistoryQuery>,
        parsed: ParsedLookupArgs,
        extra: List<Component> = emptyList(),
    ) {
        services.scope.launch {
            val flushed = services.commandApi.reads.settle()
            val search = LookupSearch(services.commandApi.rows, queries, parsed, flushed)
            searches[key(sender)] = search
            show(sender, search, parsed.page, extra)
        }
    }
}
