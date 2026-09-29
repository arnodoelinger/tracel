package com.tracel.plugin.command.action

import com.tracel.plugin.command.args.parseLookupArgs
import com.tracel.plugin.command.preset.Preset
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.command.suggest.liveLists
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `/tracel preset`: save a line of flags under a name and use it as `@name`. */
internal class PresetAction(private val store: PresetStore) {
    /** Lists what [sender] can use, each a click away from the command line. */
    fun list(sender: CommandSender) {
        val owner = (sender as? Player)?.uniqueId
        val visible = store.visibleTo(owner)
        if (visible.isEmpty()) {
            sender.sendMessage("No presets yet. /tracel preset save grief t:1h scope:30b — then /tracel rollback @grief")
            return
        }
        sender.sendMessage("Presets — click one to use it:")
        for (preset in visible) {
            val shared = if (preset.owner == null) " (server)" else ""
            sender.sendMessage(
                Component.text("  @${preset.name}", NamedTextColor.AQUA)
                    .append(Component.text("$shared  ${preset.text}", NamedTextColor.GRAY))
                    .clickEvent(ClickEvent.suggestCommand("/tracel rollback @${preset.name} "))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to start /tracel rollback @${preset.name}")))
            )
        }
    }

    /** Shows what [name] holds. */
    fun show(sender: CommandSender, name: String) {
        val preset = store.find(name, (sender as? Player)?.uniqueId)
        if (preset == null) {
            sender.sendMessage("No preset @${name.lowercase()} — /tracel preset list")
            return
        }
        sender.sendMessage("@${preset.name}${if (preset.owner == null) " (server)" else ""}: ${preset.text}")
        sender.sendMessage("  Use it: /tracel rollback @${preset.name}   Change one thing: @${preset.name} t:30m")
    }

    /** Saves [flags] as [name]; `#global` makes it the server's own. */
    fun save(sender: CommandSender, name: String, flags: List<String>) {
        val lower = name.lowercase()
        if (!PresetStore.validName(lower)) {
            sender.sendMessage("Preset names are 1–24 of a–z, 0–9, _ and -: got \"$name\".")
            return
        }
        val global = "#global" in flags
        val tokens = flags.filter { it != "#global" }
        if (tokens.isEmpty()) {
            sender.sendMessage("Nothing to save: /tracel preset save $lower t:1h scope:30b")
            return
        }
        if (tokens.any { it.startsWith("@") }) {
            sender.sendMessage("A preset cannot hold another preset: @ is for using them.")
            return
        }
        val owner = (sender as? Player)?.uniqueId
        if (global && !sender.hasPermission("tracel.preset.global")) {
            sender.sendMessage("Saving a preset for the whole server needs tracel.preset.global.")
            return
        }
        if (owner == null && !global) {
            sender.sendMessage("The console has no presets of its own — add #global to save one for the server.")
            return
        }
        val errors = parseLookupArgs(tokens, System.currentTimeMillis(), liveLists(sender)).errors
        if (errors.isNotEmpty()) {
            errors.forEach { sender.sendMessage("Preset: $it") }
            return
        }
        val target = if (global) null else owner
        if (owner != null && !global && store.find(lower, owner)?.owner == owner) {
        } else if (owner != null && !global && store.visibleTo(owner).count { it.owner == owner } >= MAX_PERSONAL) {
            sender.sendMessage("You already have $MAX_PERSONAL presets — delete one first: /tracel preset delete <name>")
            return
        }
        store.save(Preset(lower, target, tokens))
        sender.sendMessage("Saved @$lower${if (global) " for the server" else ""}: ${tokens.joinToString(" ")}")
        sender.sendMessage("  Use it: /tracel rollback @$lower")
    }

    /** Deletes [name]; a server preset needs `tracel.preset.global`. */
    fun delete(sender: CommandSender, name: String) {
        val owner = (sender as? Player)?.uniqueId
        val lower = name.lowercase()
        val mine = owner != null && store.delete(lower, owner)
        if (mine) {
            sender.sendMessage("Deleted @$lower.")
            return
        }
        val shared = store.find(lower, null)
        when {
            shared == null -> sender.sendMessage("No preset @$lower of yours — /tracel preset list")
            !sender.hasPermission("tracel.preset.global") ->
                sender.sendMessage("@$lower belongs to the server; deleting it needs tracel.preset.global.")

            else -> {
                store.delete(lower, null)
                sender.sendMessage("Deleted @$lower (server).")
            }
        }
    }

    private companion object {
        const val MAX_PERSONAL = 50
    }
}
