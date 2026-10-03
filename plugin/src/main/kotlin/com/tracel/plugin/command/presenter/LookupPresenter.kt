package com.tracel.plugin.command.presenter

import com.tracel.plugin.command.action.support.LookupSearch
import com.tracel.plugin.i18n.say
import com.tracel.plugin.i18n.send
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.usage
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import java.net.URI

object LookupPresenter {
    const val LOOKUP_PAGE: Int = 5

    /** `/tracel lookup` usage. */
    fun usage(sender: CommandSender) = sender.usage("lookup")

    /** Shows one page of [search]. */
    internal fun render(sender: CommandSender, view: LookupSearch.View, search: LookupSearch) {
        if (view.rows.isEmpty()) {
            sender.send("lookup.no_matches")
            return
        }

        val now = System.currentTimeMillis()
        if (!search.flushed) sender.send("lookup.not_flushed")
        sender.send("lookup.headline", "page" to view.page, "total" to view.total)
        sender.sendMessage(Component.empty())
        view.rows.forEach { sender.sendMessage(ChangeLinePresenter.row(it, now)) }
        if (search.parsed.command.isEmpty()) return
        sender.sendMessage(Component.empty())
        sender.sendMessage(footer(view))
    }

    /** Where the export went: a link to open, or to copy and send on. */
    internal fun exported(sender: CommandSender, link: URI, lines: Int) {
        val url = link.toString()
        val open = tr("lookup.export.button.open").clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(tr("lookup.export.button.open_hover")))
        val copy = tr("lookup.export.button.copy").clickEvent(ClickEvent.copyToClipboard(url))
            .hoverEvent(HoverEvent.showText(tr("lookup.export.button.copy_hover")))
        sender.say(report("lookup.export.done", lines, spaced(open, copy)))
    }

    @Suppress("SameParameterValue")
    private fun report(title: String, lines: Int, buttons: Component): Component = Component.join(
        JoinConfiguration.newlines(),
        tr(title),
        Component.empty(),
        tr("lookup.export.lines", "lines" to lines),
        Component.empty(),
        buttons,
    )

    private fun footer(view: LookupSearch.View): Component {
        fun turn(key: String, page: Int) = tr("lookup.button.$key")
            .clickEvent(ClickEvent.runCommand("/tracel lookup p:$page"))
            .hoverEvent(HoverEvent.showText(tr("lookup.hover.page", "page" to page)))

        val total = view.total
        val export = tr("lookup.button.export")
            .clickEvent(ClickEvent.runCommand("/tracel lookup #export"))
            .hoverEvent(HoverEvent.showText(tr("lookup.hover.export")))
        val refresh = tr("lookup.button.refresh")
            .clickEvent(ClickEvent.runCommand("/tracel lookup #refresh p:${view.page}"))
            .hoverEvent(HoverEvent.showText(tr("lookup.hover.refresh")))
        if (total == 1) return spaced(export, refresh)

        val backTo = if (view.page > 1) view.page - 1 else total
        val nextTo = if (view.page < total) view.page + 1 else 1
        val back = turn("back", backTo)
        val next = turn("next", nextTo)
        return spaced(back, next, export, refresh)
    }

    private fun spaced(vararg parts: Component): Component =
        Component.join(JoinConfiguration.separator(Component.space()), *parts)
}
