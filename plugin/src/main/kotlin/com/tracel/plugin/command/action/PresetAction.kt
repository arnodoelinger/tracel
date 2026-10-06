package com.tracel.plugin.command.action

import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.args.lookup.parseLookupArgs
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.command.preset.Preset
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.i18n.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/tracel preset`: save a line of flags under a name and use it as `@name`. */
internal class PresetAction(private val store: PresetStore, private val nothing: NothingWeCanDo) {
    private companion object {
        const val MAX_PERSONAL = 50
    }

    /** Lists what [sender] can use, each a click away from the command line. */
    fun list(sender: CommandSender) {
        val visible = store.visibleTo((sender as? Player)?.uniqueId)
        if (visible.isEmpty()) {
            report(
                sender,
                tr("preset.list.title"),
                Component.empty(),
                info(tr("preset.list.empty.info")),
                tryHint(tr("preset.list.empty.hint"))
            )
            return
        }
        val entries = visible.map { preset ->
            tr(
                if (preset.owner == null) "preset.list.entry_server" else "preset.list.entry",
                "name" to preset.name,
                "flags" to preset.text
            )
                .clickEvent(ClickEvent.suggestCommand("/tracel rollback @${preset.name} "))
                .hoverEvent(HoverEvent.showText(tr("preset.list.hover", "name" to preset.name)))
        }
        report(sender, tr("preset.list.title"), Component.empty(), *entries.toTypedArray())
    }

    /** Shows what [name] holds. */
    fun show(sender: CommandSender, name: String) {
        val preset = store.find(name, (sender as? Player)?.uniqueId)
        if (preset == null) {
            refuse(sender, tr("preset.reason.missing", "name" to name.lowercase()), tr("preset.hint.list"))
            return
        }
        report(
            sender,
            tr("preset.show", "name" to preset.name),
            Component.empty(),
            tr("preset.label.flags", "flags" to preset.text),
            tr("preset.label.for", "who" to who(preset.owner == null)),
            Component.empty(),
            buttons(sender, preset),
        )
    }

    /** Saves [flags] as [name], for [sender]; the console has no presets of its own, so what it saves is the server's. */
    fun save(sender: CommandSender, name: String, flags: List<String>) {
        val lower = name.lowercase()
        if (!PresetStore.validName(lower)) {
            refuse(sender, tr("preset.reason.bad_name", "name" to name), tr("preset.hint.name"))
            return
        }
        val tokens = flags
        if (tokens.isEmpty()) {
            refuse(sender, tr("preset.reason.add_empty"), tr("preset.hint.add"))
            return
        }
        if (tokens.any { it.startsWith("@") }) {
            refuse(sender, tr("preset.reason.add_nested"), tr("preset.hint.nested"))
            return
        }
        val owner = (sender as? Player)?.uniqueId
        val errors = parseLookupArgs(tokens, System.currentTimeMillis()).errors
        if (errors.isNotEmpty()) {
            refuse(sender, errors.asReason(), tr("common.hint.fix_flags", "command" to "lookup"))
            return
        }
        val replacing = owner != null && store.find(lower, owner)?.owner == owner
        if (owner != null && !replacing && store.visibleTo(owner).count { it.owner == owner } >= MAX_PERSONAL) {
            refuse(sender, tr("preset.reason.too_many", "limit" to MAX_PERSONAL), tr("preset.hint.delete"))
            return
        }
        val saved = Preset(lower, owner, tokens)
        store.save(saved)
        report(
            sender,
            tr("preset.saved"),
            Component.empty(),
            tr("preset.label.name", "name" to lower),
            tr("preset.label.flags", "flags" to tokens.joinToString(" ")),
            tr("preset.label.for", "who" to who(owner == null)),
            Component.empty(),
            buttons(sender, saved),
        )
    }

    /** Hands one of [sender]'s own presets to the server. */
    fun share(sender: CommandSender, name: String) {
        val owner = (sender as? Player)?.uniqueId
        val lower = name.lowercase()
        if (!sender.has(Permission.PRESET_GLOBAL)) return refuse(
            sender,
            tr("preset.reason.global_perm"),
            tr("preset.hint.admin")
        )
        if (owner == null || store.find(lower, owner)?.owner != owner) {
            if (store.find(lower, null) != null) {
                return refuse(
                    sender,
                    tr("preset.reason.already", "name" to lower, "who" to who(true)),
                    nothing.on(tr("common.nothing"))
                )
            }
            return refuse(sender, tr("preset.reason.missing", "name" to lower), tr("preset.hint.list"))
        }
        if (!store.move(lower, owner, null)) {
            return refuse(sender, tr("preset.reason.taken", "name" to lower), tr("preset.hint.rename"))
        }
        report(
            sender,
            tr("preset.shared"),
            Component.empty(),
            tr("preset.label.name", "name" to lower),
            tr("preset.label.for", "who" to who(true)),
        )
    }

    /** Takes one of the server's presets back as [sender]'s own. */
    fun unshare(sender: CommandSender, name: String) {
        val owner = (sender as? Player)?.uniqueId
        val lower = name.lowercase()
        if (!sender.has(Permission.PRESET_GLOBAL)) return refuse(
            sender,
            tr("preset.reason.global_perm"),
            tr("preset.hint.admin")
        )
        if (owner == null) return refuse(sender, tr("preset.reason.console"), tr("preset.hint.delete"))
        if (store.find(lower, null) == null) {
            if (store.find(lower, owner) != null) {
                return refuse(
                    sender,
                    tr("preset.reason.already", "name" to lower, "who" to who(false)),
                    nothing.on(tr("common.nothing"))
                )
            }
            return refuse(sender, tr("preset.reason.missing", "name" to lower), tr("preset.hint.list"))
        }
        if (!store.move(lower, null, owner)) {
            return refuse(sender, tr("preset.reason.taken", "name" to lower), tr("preset.hint.delete"))
        }
        report(
            sender,
            tr("preset.unshared"),
            Component.empty(),
            tr("preset.label.name", "name" to lower),
            tr("preset.label.for", "who" to who(false)),
        )
    }

    /** Deletes [name]; a server preset needs a permission. */
    fun delete(sender: CommandSender, name: String) {
        val owner = (sender as? Player)?.uniqueId
        val lower = name.lowercase()
        if (owner != null && store.delete(lower, owner)) {
            deleted(sender, lower, server = false)
            return
        }
        when {
            store.find(lower, null) == null ->
                refuse(sender, tr("preset.reason.missing", "name" to lower), tr("preset.hint.list"))

            !sender.has(Permission.PRESET_GLOBAL) ->
                refuse(sender, tr("preset.reason.global_perm"), tr("preset.hint.admin"))

            else -> {
                store.delete(lower, null)
                deleted(sender, lower, server = true)
            }
        }
    }

    private fun deleted(sender: CommandSender, name: String, server: Boolean) = report(
        sender,
        tr("preset.deleted"),
        Component.empty(),
        tr("preset.label.name", "name" to name),
        tr("preset.label.for", "who" to who(server)),
    )

    private fun buttons(sender: CommandSender, preset: Preset): Component {
        val server = preset.owner == null
        val mayChangeServer = sender.has(Permission.PRESET_GLOBAL)
        val line = Component.text().append(useButton(preset.name))
        if (sender is Player && mayChangeServer) {
            val key = if (server) "unshare" else "share"
            line.append(Component.space())
                .append(run("preset.button.$key", "/tracel preset $key ${preset.name}", preset.name))
        }
        if (!server || mayChangeServer) {
            line.append(Component.space())
                .append(run("preset.button.delete", "/tracel preset delete ${preset.name}", preset.name))
        }
        return line.build()
    }

    private fun run(key: String, command: String, name: String): Component = tr(key)
        .clickEvent(ClickEvent.runCommand(command))
        .hoverEvent(HoverEvent.showText(tr("${key}_hover", "name" to name)))

    private fun useButton(name: String): Component = tr("preset.button.use")
        .clickEvent(ClickEvent.suggestCommand("/tracel rollback @$name "))
        .hoverEvent(HoverEvent.showText(tr("preset.button.use_hover", "name" to name)))

    private fun who(server: Boolean): Component = tr(if (server) "preset.for.server" else "preset.for.you")

    private fun report(sender: CommandSender, vararg lines: Component) =
        sender.say(Component.join(JoinConfiguration.newlines(), *lines))

    private fun refuse(sender: CommandSender, reason: Component, hint: Component) =
        sender.failed("preset.failed", reason, hint)
}
