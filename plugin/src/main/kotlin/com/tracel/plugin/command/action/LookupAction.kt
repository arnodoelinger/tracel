package com.tracel.plugin.command.action

import com.tracel.annotations.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.engine.log.LookupFilter
import com.tracel.engine.log.LookupRegion
import com.tracel.model.holder.HolderId
import com.tracel.model.id.WorldId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.support.LookupSearch
import com.tracel.plugin.command.args.*
import com.tracel.plugin.command.args.support.MaterialAliases
import com.tracel.plugin.command.presenter.ChangeLinePresenter
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.i18n.*
import com.tracel.plugin.util.PrivateBin
import com.tracel.plugin.util.resolvePlayerUuid
import com.tracel.plugin.util.toLookupRegion
import com.tracel.plugin.util.toWorldId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Action responsible for executing transaction and world log lookups. */
class LookupAction(private val services: TracelServices) {
    private val searches = ConcurrentHashMap<Any, LookupSearch>()

    private fun key(sender: CommandSender): Any = (sender as? Player)?.uniqueId ?: sender.name

    private companion object {
        const val MAX_LINES = 50_000
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
                val stacks = search.all()
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
    fun inspect(sender: CommandSender, block: Block) {
        val world = WorldId(block.world.uid)
        val region = LookupRegion(
            world,
            block.x shr 4, block.x shr 4, block.z shr 4, block.z shr 4,
            block.x, block.x, block.y, block.y, block.z, block.z,
        )
        val filter = LookupFilter(region = region, world = world, limit = Int.MAX_VALUE)
        val parsed = ParsedLookupArgs(anchor = block.location, command = "lookup block")
        runLookup(sender, filter, parsed, ActionArgument.parse(emptySet()))
    }

    /** Executes lookup. */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (parsed.errors.isNotEmpty()) {
            refuse(sender, parsed.errors)
            return
        }

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

        val filter = LookupFilter(
            holders = users.map(HolderId::Player).toSet(),
            material = parsed.item?.let { MaterialAliases.resolve(it).first },
            blockMaterials = parsed.item?.let { MaterialAliases.resolve(it).second }.orEmpty(),
            causes = actions.causes,
            excludedCauses = if (parsed.natural || "world" in parsed.actions.map(String::lowercase)) emptySet() else setOf(
                CauseKind.WORLD
            ),
            worldCauses = actions.worldCauses,
            actions = actions.actions,
            since = parsed.since,
            until = parsed.until,
            region = center?.toLookupRegion(scope, parsed.horizontalOnly),
            world = named.toWorldId(),
            limit = Int.MAX_VALUE,
        )

        runLookup(sender, filter, parsed.copy(anchor = center), actions)
    }

    private fun show(sender: CommandSender, search: LookupSearch, page: Int) {
        services.scope.launch {
            val view = try {
                search.page(page)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.failed("lookup.failed", Component.text(unexpected(failure)), tr("lookup.hint.again"))
                return@launch
            }
            LookupPresenter.render(sender, view, search)
        }
    }

    private fun refuse(sender: CommandSender, reasons: List<Component>) =
        sender.failed("lookup.failed", reasons.asReason(), tr("common.hint.fix_flags", "command" to "lookup"))

    private fun runLookup(
        sender: CommandSender,
        filter: LookupFilter,
        parsed: ParsedLookupArgs,
        actions: ActionFilter,
    ) {
        services.scope.launch {
            val flushed = services.flushCapture()
            val search = LookupSearch(
                services = services,
                filter = filter,
                parsed = parsed,
                blocks = actions.structural && !parsed.materialOnly,
                items = actions.material && !parsed.structureOnly,
                flushed = flushed,
                events = if (parsed.all && parsed.actions.isEmpty()) EventKind.entries.toSet() else actions.events,
            )
            searches[key(sender)] = search
            show(sender, search, parsed.page)
        }
    }
}
