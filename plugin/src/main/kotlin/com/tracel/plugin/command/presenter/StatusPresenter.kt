package com.tracel.plugin.command.presenter

import com.tracel.engine.store.PurgeCategory
import com.tracel.plugin.command.action.shortSpan
import com.tracel.plugin.config.AutoPurgeSettings
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.status.rollback.RollbackSize
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.HoverEvent

/** The lines of `/tracel status`. */
internal object StatusPresenter {
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** The whole status, one line per fact. */
    fun render(status: StatusSnapshot): Component = Component.join(
        JoinConfiguration.newlines(),
        buildList {
            add(tr("status.title"))
            add(Component.empty())
            add(tr("status.format", "version" to status.format))
            add(Component.empty())
            add(records(status))
            add(period(status))
            add(tr("status.size", "size" to bytes(status.databaseBytes)))
            add(disk(status))
            add(purge(status))
            add(Component.empty())
            add(tr("status.queue", "n" to status.queued))
            add(load(status))
            add(rollbacks(status))
            add(Component.empty())
            add(state(status))
        },
    )

    private fun records(status: StatusSnapshot): Component = tr(
        "status.records",
        "blocks" to count(status.blockRows),
        "items" to count(status.itemRows),
        "events" to count(status.eventRows),
    )

    private fun period(status: StatusSnapshot): Component {
        val oldest = status.oldestMillis ?: return tr("status.period_empty")
        return tr(
            "status.period",
            "from" to STAMP.format(Instant.ofEpochMilli(oldest)),
            "span" to shortSpan(status.nowMillis - oldest),
        )
    }

    private fun disk(status: StatusSnapshot): Component = tr(
        "status.disk",
        "free" to bytes(status.diskFree),
        "percent" to percent(status),
    )

    private fun purge(status: StatusSnapshot): Component {
        val settings = status.purge
        if (!settings.enabled) return tr("status.purge_off")
        val last = status.lastPurgeMillis?.let { STAMP.format(Instant.ofEpochMilli(it)) }
        val keeps = settings.keep
        val star = if (keeps.values.toSet().size <= 1) Component.empty() else tr("status.purge_star")
            .hoverEvent(HoverEvent.showText(purgeHover(settings)))
        return tr(
            "status.purge_on",
            "star" to star,
            "last" to (last?.let { Component.text(it) } ?: tr("status.purge_never")),
        )
    }

    private fun purgeHover(settings: AutoPurgeSettings): Component = Component.join(
        JoinConfiguration.newlines(),
        buildList {
            add(tr("status.hover.purge_interval", "interval" to shortSpan(settings.intervalMillis)))
            for (category in PurgeCategory.entries) {
                add(
                    tr(
                        "status.hover.purge_keep",
                        "category" to tr("status.category.${category.name.lowercase()}"),
                        "keep" to (settings.keep[category]?.let { Component.text(shortSpan(it)) }
                            ?: tr("status.forever")),
                    )
                )
            }
        },
    )

    private fun load(status: StatusSnapshot): Component {
        val rate = status.writesPerSecond ?: return tr("status.load_unknown")
        return tr("status.load", "rate" to count(Math.round(rate)))
    }

    private fun rollbacks(status: StatusSnapshot): Component {
        if (status.rollbacks.isEmpty()) return tr("status.rollbacks_none")
        val sizes = status.rollbacks.map { tr("status.size_word.${RollbackSize.of(it).key}") }
        return tr(
            "status.rollbacks",
            "n" to sizes.size,
            "sizes" to Component.join(JoinConfiguration.separator(Component.text(", ")), sizes),
        )
    }

    private fun state(status: StatusSnapshot): Component {
        val health = status.health
        val lag = status.lagMillis?.let { millis ->
            if (status.lagBeyondProbe) ">" + shortSpan(millis) else shortSpan(millis)
        } ?: ""
        val word = tr(
            "status.health.${health.key}",
            "span" to lag,
            "free" to bytes(status.diskFree),
            "percent" to percent(status),
            "mspt" to "%.0f".format(Locale.ENGLISH, status.mspt ?: 0.0),
        )
        val hover = tr(
            "status.hover.${health.key}",
            "span" to lag,
            "free" to bytes(status.diskFree),
            "percent" to percent(status),
            "mspt" to "%.1f".format(Locale.ENGLISH, status.mspt ?: 0.0),
        )
        return tr("status.state", "state" to word.hoverEvent(HoverEvent.showText(hover)))
    }

    private fun percent(status: StatusSnapshot): String =
        if (status.diskTotal <= 0L) "?" else (status.diskFree * 100 / status.diskTotal).toString()

    private fun count(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)

    private fun bytes(n: Long): String {
        val gib = n / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) "%.1f GB".format(Locale.ENGLISH, gib)
        else "%.1f MB".format(Locale.ENGLISH, n / (1024.0 * 1024.0))
    }
}
