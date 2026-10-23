package com.tracel.plugin.command.action

import com.tracel.api.history.BlockRecord
import com.tracel.api.history.HistoryCursor
import com.tracel.api.history.RecordKind
import com.tracel.api.result.TracelException
import com.tracel.api.result.getOrElse
import com.tracel.plugin.command.args.lookup.ParsedLookupArgs
import com.tracel.plugin.command.args.rollback.FilterResult
import com.tracel.plugin.command.args.rollback.RollbackArgument
import com.tracel.plugin.command.scrub.ScrubTimelineBuilder
import com.tracel.plugin.i18n.*
import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/tracel scrub`: the mouse wheel as a time machine for the blocks around you. */
internal class ScrubAction(private val services: TracelServices) {
    /** Reads the blocks the flags name and puts [sender] into the scrub of them. */
    fun execute(sender: CommandSender, parsed: ParsedLookupArgs) {
        if (sender !is Player) return sender.send("scrub.players_only")
        if (parsed.errors.isNotEmpty()) return refuse(sender, parsed.errors.asReason())

        when (val filter = RollbackArgument.build(sender, parsed, wholeWorld = true)) {
            is FilterResult.Rejected -> refuse(sender, filter.reasons.asReason())
            is FilterResult.Ok -> {
                val job = services.scope.launch(start = CoroutineStart.LAZY) { scrub(sender, filter) }
                services.scrubs.reading(sender, job)
                job.invokeOnCompletion { services.scrubs.read(sender, job) }
                job.start()
            }
        }
    }

    /** Ends the scrub of [sender], or the read of it that is still going. */
    fun stop(sender: CommandSender) {
        if (sender !is Player) return sender.send("scrub.players_only")
        if (services.scrubs.cancelRead(sender)) return sender.send("scrub.stopped")
        if (services.scrubs.of(sender) == null) {
            return sender.failed("scrub.failed", tr("scrub.reason.none"), tr("scrub.hint.none"))
        }
        services.scrubs.end(sender)
        sender.send("scrub.stopped")
    }

    private suspend fun scrub(player: Player, filter: FilterResult.Ok) {
        val query = filter.query.copy(kinds = setOf(RecordKind.BLOCK))
        val world = query.area?.world ?: return refuse(player, tr("common.reason.need_scope"))
        val reads = services.commandApi.reads
        reads.settle()

        val builder = ScrubTimelineBuilder(world)
        try {
            var told = System.nanoTime()
            val history = reads.history(query)
            var from: HistoryCursor? = null
            do {
                val page = history.page(from, PAGE).getOrElse { throw TracelException(it) }
                for (record in page.records) {
                    if (record is BlockRecord) builder.add(record)
                    val now = System.nanoTime()
                    if (now - told >= PROGRESS_NANOS) {
                        told = now
                        player.sendActionBar(tr("scrub.reading", "records" to builder.records))
                    }
                }
                from = page.next
            } while (from != null)
            val timeline = builder.build()
            if (timeline.size == 0) {
                timeline.close()
                return player.failed("scrub.failed", tr("scrub.reason.nothing"), tr("scrub.hint.nothing"))
            }
            services.scrubs.begin(player, timeline)
            player.report(
                tr("scrub.started"),
                Component.empty(),
                tr("scrub.controls.scroll"),
                tr("scrub.controls.shift"),
                tr("scrub.controls.play"),
                tr("scrub.controls.leave"),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            player.failed("scrub.failed", Component.text(unexpected(failure)), tr("scrub.hint.again"))
        } finally {
            builder.close()
        }
    }

    private fun Audience.report(vararg lines: Component) = say(Component.join(JoinConfiguration.newlines(), *lines))

    private fun refuse(sender: CommandSender, reason: Component) =
        sender.failed("scrub.failed", reason, tr("common.hint.fix_flags", "command" to "scrub"))

    private companion object {
        const val PROGRESS_NANOS = 1_000_000_000L
        const val PAGE = (1 shl 20) - 1
    }
}
