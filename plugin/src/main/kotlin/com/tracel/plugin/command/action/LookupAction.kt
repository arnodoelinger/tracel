package com.tracel.plugin.command.action

import com.tracel.engine.log.LookupFilter
import com.tracel.model.holder.HolderId
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.args.*
import com.tracel.plugin.command.args.ScopeLimits.isOversized
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.util.resolvePlayerUuid
import com.tracel.plugin.util.toLookupRegion
import com.tracel.plugin.util.toWorldId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** Action responsible for executing transaction and world log lookups. */
class LookupAction(private val services: TracelServices) {
    /** Executes lookup. */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (parsed.errors.isNotEmpty()) {
            sender.sendMessage("Lookup errors: ${parsed.errors.joinToString(", ")}")
            LookupPresenter.usage(sender)
            return
        }

        val unresolvedUsers = parsed.users.filter { resolvePlayerUuid(it) == null }
        if (unresolvedUsers.isNotEmpty()) {
            sender.sendMessage("Unknown player(s): ${unresolvedUsers.joinToString(", ")}")
            return
        }
        val unresolvedExcluded = parsed.excludedUsers.filter { resolvePlayerUuid(it) == null }
        if (unresolvedExcluded.isNotEmpty()) {
            sender.sendMessage("Unknown player(s) to exclude: ${unresolvedExcluded.joinToString(", ")}")
            return
        }
        if (parsed.lot != null) {
            sender.sendMessage("Lookup: l: only works with rollback; a lookup cannot list one lot's history.")
            return
        }
        val users = parsed.users.mapNotNull { resolvePlayerUuid(it) }
        val excludedUsers = parsed.excludedUsers.mapNotNull { resolvePlayerUuid(it) }

        val actions = ActionArgument.parse(parsed.actions)
        if (actions.unknown.isNotEmpty()) {
            sender.sendMessage(
                "Unknown action(s): ${actions.unknown.joinToString(", ")} — known: ${
                    ActionArgument.NAMES.joinToString(",")
                }"
            )
            return
        }

        val scope = parsed.scope
        val named = parsed.world?.let { Bukkit.getWorld(it) }
        if (parsed.world != null && named == null) {
            sender.sendMessage("Unknown world: ${parsed.world}")
            return
        }
        val center = if (scope != null) (sender as? Player)?.location else null
        if (scope != null && center == null) {
            sender.sendMessage("scope:${ScopeArgument.describe(scope)} needs a player location — run this as a player, or use w:<world> for a whole-world search.")
            return
        }
        if (scope != null && named != null && center != null && center.world?.uid != named.uid) {
            sender.sendMessage("scope:${ScopeArgument.describe(scope)} is a cube around you and you are not in ${named.name}.")
            return
        }
        if (scope != null && scope.isOversized()) {
            sender.sendMessage(
                "Lookup: radius is too large (max ${ScopeLimits.MAX_BLOCK_RADIUS} blocks / " +
                        "${ScopeLimits.MAX_CHUNK_RADIUS} chunks) — use scope:${ScopeLimits.MAX_BLOCK_RADIUS}b " +
                        "or scope:${ScopeLimits.MAX_CHUNK_RADIUS}c"
            )
            return
        }

        val filter = LookupFilter(
            holders = users.map(HolderId::Player).toSet(),
            excludedHolders = excludedUsers.map(HolderId::Player).toSet(),
            material = parsed.item?.let { MaterialAliases.resolve(it).first },
            blockMaterials = parsed.item?.let { MaterialAliases.resolve(it).second }.orEmpty(),
            causes = actions.causes,
            worldCauses = actions.worldCauses,
            actions = actions.actions,
            since = parsed.since,
            until = parsed.until,
            region = center?.toLookupRegion(scope, parsed.horizontalOnly),
            world = named.toWorldId(),
            limit = LookupPresenter.LOOKUP_PAGE,
        )

        runLookup(sender, filter, parsed, actions)
    }

    private fun runLookup(
        sender: CommandSender,
        filter: LookupFilter,
        parsed: ParsedLookupArgs,
        actions: ActionFilter,
    ) {
        services.scope.launch {
            val flushed = services.flushCapture()
            val (results, worldChanges) = try {
                coroutineScope {
                    val txns = async {
                        if (!actions.material || parsed.structureOnly) emptyList() else services.reading {
                            services.log.query(
                                filter
                            )
                        }
                    }
                    val world = async {
                        if (!actions.structural || parsed.materialOnly) emptyList() else services.reading {
                            services.worldLog.query(
                                filter
                            )
                        }
                    }
                    Pair(txns.await(), world.await())
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                sender.sendMessage("Lookup failed: ${failure.message ?: failure::class.java.simpleName}")
                return@launch
            }

            LookupPresenter.renderResults(
                sender = sender,
                results = results,
                worldChanges = worldChanges,
                parsed = parsed,
                limit = filter.limit,
                flushed = flushed,
            )
        }
    }
}
