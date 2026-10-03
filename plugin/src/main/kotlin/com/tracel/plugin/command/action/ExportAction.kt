package com.tracel.plugin.command.action

import com.tracel.plugin.TracelServices
import com.tracel.plugin.i18n.*
import com.tracel.storage.ports.ops.ExportSummary
import com.tracel.storage.ports.ops.StoppedByRequest
import com.tracel.storage.ports.ops.exportTo
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/** Writes `Tracel` database snapshots to the export directory. */
class ExportAction(private val services: TracelServices) {
    private val running = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    /** Shows how much disk the export may take, and how much is left, before it is run for real. */
    fun confirmExport(sender: CommandSender) {
        val locale = localeOf(sender)
        val size = services.storage.engine.stats().liveBytes
        val free = freeSpace(services.exportDirectory)
        val args = arrayOf("size" to "%.1f".format(locale, size / MIB), "free" to "%.1f".format(locale, free / MIB))
        sender.say(
            Component.join(
                JoinConfiguration.newlines(),
                tr("export.preview.title"),
                Component.empty(),
                info(tr(if (free < size) "export.confirm.low" else "export.confirm.info", *args)),
                Component.empty(),
                confirmFooter("/tracel data export", backup = false),
            ),
        )
    }

    /** Exports the current `Tracel` database snapshot to a file in the export directory. */
    fun executeExport(sender: CommandSender) {
        val name = "database-%s.tracel".format(LocalDateTime.now().format(STAMP))
        val to = services.exportDirectory.resolve(name)

        if (!running.compareAndSet(false, true)) return sender.send("common.busy")
        stopping.set(false)
        sender.send("export.start")

        services.scope.launch {
            val done = try {
                runCatching { exportTo(services.storage, to, stopping::get) }
            } finally {
                running.set(false)
            }
            done.onSuccess { sender.say(report(it, localeOf(sender))) }
                .onFailure {
                    if (it is StoppedByRequest) sender.send("export.stopped")
                    else sender.fail("export.failed", it)
                }
        }
    }

    /** Stops the running export; the half-written file goes with it. */
    fun stop(sender: CommandSender) {
        if (!running.get()) return sender.send("export.not_running")
        stopping.set(true)
        sender.send("export.stop")
    }

    private fun freeSpace(directory: Path): Long {
        var existing: Path? = directory.toAbsolutePath()
        while (existing != null && !Files.exists(existing)) existing = existing.parent
        return runCatching { Files.getFileStore(existing ?: directory).usableSpace }.getOrDefault(0L)
    }

    private fun report(summary: ExportSummary, locale: Locale): Component {
        val file = summary.file.fileName.toString()
        val lines = mutableListOf(
            tr("export.done"),
            Component.empty(),
            tr("common.label.database", "file" to file),
            tr("common.label.size", "size" to "%.1f".format(locale, summary.bytes / MIB)),
        )
        records("common.label.records", summary.oldest, summary.newest, locale)?.let { lines += it }
        lines += tr("common.label.rows", "rows" to "%,d".format(locale, summary.rows))
        lines += Component.empty()

        val path = summary.file.toAbsolutePath().toString()
        val copy = tr("export.button.file").clickEvent(ClickEvent.copyToClipboard(path))
            .hoverEvent(HoverEvent.showText(tr("export.button.file_hover", "path" to path)))
        val load = tr("export.button.import").clickEvent(ClickEvent.suggestCommand("/tracel data import $file"))
            .hoverEvent(HoverEvent.showText(tr("export.button.import_hover")))
        lines += copy.append(Component.space()).append(load)
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun CommandSender.fail(key: String, reason: Throwable) {
        val cause = when {
            reason is AccessDeniedException || reason is ReadOnlyFileSystemException ->
                Cause("common.reason.denied", "common.hint.denied", "folder" to services.exportDirectory.toString())

            reason is FileSystemException && reason.reason?.contains("No space left") == true ->
                Cause("common.reason.no_space", "common.hint.no_space")

            else -> null
        }
        if (cause == null) fail(key, Component.text(unexpected(reason)), tr("common.hint.unknown"))
        else fail(key, tr(cause.reason, *cause.args), tr(cause.hint))
    }

    private class Cause(val reason: String, val hint: String, vararg val args: Pair<String, Any?>)

    private fun CommandSender.fail(key: String, reason: Component, hint: Component) = failed(key, reason, hint)

    private fun localeOf(sender: CommandSender): Locale = (sender as? Player)?.locale() ?: Locale.ENGLISH

    companion object {
        const val MIB = 1024.0 * 1024.0

        internal fun records(key: String, oldest: Long?, newest: Long?, locale: Locale): Component? {
            if (oldest == null || newest == null) return null
            val stamp = DateTimeFormatter.ofPattern("d MMM HH:mm", locale)
            fun at(millis: Long) = stamp.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
            return tr(key, "from" to at(oldest), "to" to at(newest))
        }

        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")
    }
}
