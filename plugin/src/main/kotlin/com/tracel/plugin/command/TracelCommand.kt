package com.tracel.plugin.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.args.LookupScope
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.preset.PresetAction
import com.tracel.plugin.command.suggest.PlayerNameSuggest
import com.tracel.plugin.command.who.WhoAction
import com.tracel.plugin.command.preset.PresetNameSuggest
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.command.preset.Presets
import com.tracel.plugin.command.preset.parseWithPresets
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.suggest.ExportSuggest
import com.tracel.plugin.command.suggest.LookupSuggest
import com.tracel.plugin.command.suggest.liveLists
import com.tracel.plugin.command.suggest.RollbackSuggest
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/** `Tracel` commands. */
object TracelCommand {
    private const val NEAR_WINDOW = 15 * 60_000L
    private const val NEAR_RADIUS = 16

    /** Registers all `Tracel` commands. */
    fun register(registrar: Commands, services: TracelServices) {
        val undo = UndoAction(services)
        val highlights = Highlights(services.plugin)
        val rollback = RollbackAction(services, highlights)
        val lookup = LookupAction(services)
        val inspect = InspectAction(services)
        val purge = PurgeAction(services)
        val export = ExportAction(services)
        val store = PresetStore(services.plugin.dataFolder.toPath().resolve("presets.tsv"))
        Presets.store = store
        val presets = PresetAction(store)
        val who = WhoAction(services)

        val root = literal("tracel") {
            executesCommand { ctx -> sendHelp(ctx.source.sender) }

            literal("rollback", "Roll back world and container changes") {
                requiresPermission("tracel.rollback")
                executesCommand { ctx -> RollbackPresenter.usage(ctx.source.sender) }
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(RollbackSuggest)
                    executesCommand { ctx ->
                        val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                        rollback.execute(ctx.source.sender, parsePresetted(ctx.source.sender, tokens, store))
                    }
                }
            }

            literal("restore", "Take back the last rollback") {
                requiresPermission("tracel.rollback")
                takeBack(undo)
            }

            literal("lookup", "Inspect transaction and world logs") {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> LookupPresenter.usage(ctx.source.sender) }
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(LookupSuggest)
                    executesCommand { ctx ->
                        val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                        lookup.execute(ctx.source.sender, parsePresetted(ctx.source.sender, tokens, store))
                    }
                }
            }

            literal("preset", "Save flags under a name, use them as @name") {
                requiresPermission("tracel.preset")
                executesCommand { ctx -> presets.list(ctx.source.sender) }
                literal("list", "Your presets and the server's") {
                    executesCommand { ctx -> presets.list(ctx.source.sender) }
                }
                literal("save", "Save flags under a name") {
                    executesCommand { ctx ->
                        ctx.source.sender.sendMessage("Usage: /tracel preset save <name> <flags> — for example: grief t:1h scope:30b")
                    }
                    argument("name", StringArgumentType.word()) {
                        suggests(PresetNameSuggest)
                        argument("flags", StringArgumentType.greedyString()) {
                            suggests(RollbackSuggest)
                            executesCommand { ctx ->
                                presets.save(
                                    ctx.source.sender,
                                    StringArgumentType.getString(ctx, "name"),
                                    tokens(StringArgumentType.getString(ctx, "flags")),
                                )
                            }
                        }
                    }
                }
                literal("show", "Show what a preset holds") {
                    argument("name", StringArgumentType.word()) {
                        suggests(PresetNameSuggest)
                        executesCommand { ctx -> presets.show(ctx.source.sender, StringArgumentType.getString(ctx, "name")) }
                    }
                }
                literal("delete", "Delete a preset") {
                    argument("name", StringArgumentType.word()) {
                        suggests(PresetNameSuggest)
                        executesCommand { ctx -> presets.delete(ctx.source.sender, StringArgumentType.getString(ctx, "name")) }
                    }
                }
            }

            literal("near", "What happened around you lately") {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> runNear(ctx.source.sender, emptyList(), store, lookup) }
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(LookupSuggest)
                    executesCommand { ctx ->
                        val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                        runNear(ctx.source.sender, tokens, store, lookup)
                    }
                }
            }

            literal("who", "What a player did, on one screen") {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> ctx.source.sender.sendMessage("Usage: /tracel who <player> [time] [scope] — default: the last day") }
                argument("player", StringArgumentType.word()) {
                    suggests(PlayerNameSuggest)
                    executesCommand { ctx ->
                        who.execute(ctx.source.sender, StringArgumentType.getString(ctx, "player"), ParsedLookupArgs())
                    }
                    argument("flags", StringArgumentType.greedyString()) {
                        suggests(LookupSuggest)
                        executesCommand { ctx ->
                            val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                            who.execute(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "player"),
                                parsePresetted(ctx.source.sender, tokens, store),
                            )
                        }
                    }
                }
            }

            literal("inspect", "Toggle the block inspector") {
                requiresPermission("tracel.inspect")
                executesCommand { ctx -> inspect.execute(ctx.source.sender) }
            }

            literal("purge", "Wipe the history database") {
                requiresPermission("tracel.purge")
                executesCommand { ctx ->
                    ctx.source.sender.sendMessage(
                        "Usage: /tracel purge confirm — wipes the entire Tracel database, with no undo."
                    )
                }
                literal("confirm", "Yes, wipe the whole history — there is no undo") {
                    executesCommand { ctx -> purge.execute(ctx.source.sender) }
                }
            }

            literal("export", "Snapshot export and import") {
                requiresPermission("tracel.export")
                executesCommand { ctx -> export.executeExport(ctx.source.sender) }
                literal("import", "Replace the whole history with a snapshot file") {
                    executesCommand { ctx ->
                        ctx.source.sender.sendMessage(
                            "Usage: /tracel export import <file> confirm — replaces the entire history with that file."
                        )
                    }
                    argument("file", StringArgumentType.string()) {
                        suggests(ExportSuggest.suggesting(services.exportDirectory))
                        executesCommand { ctx ->
                            val file = StringArgumentType.getString(ctx, "file")
                            ctx.source.sender.sendMessage(
                                "Usage: /tracel export import $file confirm — confirm is required to proceed."
                            )
                        }
                        literal("confirm", "Yes, replace everything recorded so far") {
                            executesCommand { ctx ->
                                val file = StringArgumentType.getString(ctx, "file")
                                export.executeImport(ctx.source.sender, file)
                            }
                        }
                    }
                }
            }
        }

        registrar.register(root.build(), "Tracel commands.", listOf("tr"))
        registrar.answerSuggestionsFromServer("tracel", "tr")
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("Tracel — forensics and history engine.")
        if (sender.hasPermission("tracel.rollback")) {
            sender.sendMessage(" /tracel rollback <flags> — roll back world and container changes")
            sender.sendMessage(" /tracel restore — take back the last rollback")
        }
        if (sender.hasPermission("tracel.lookup")) {
            sender.sendMessage(" /tracel near — what happened around you in the last minutes")
            sender.sendMessage(" /tracel who <player> — what a player did, on one screen")
        }
        if (sender.hasPermission("tracel.preset")) {
            sender.sendMessage(" /tracel preset save <name> <flags> — save flags, then use them as @name")
        }
        if (sender.hasPermission("tracel.lookup")) {
            sender.sendMessage(" /tracel lookup <flags> — inspect transaction and world logs")
        }
        if (sender.hasPermission("tracel.inspect")) {
            sender.sendMessage(" /tracel inspect — toggle block inspector")
        }
        if (sender.hasPermission("tracel.export")) {
            sender.sendMessage(" /tracel export [import <file> confirm] — snapshot export and import")
        }
        if (sender.hasPermission("tracel.purge")) {
            sender.sendMessage(" /tracel purge confirm — wipe the history database")
        }
    }

    private fun runNear(sender: CommandSender, tokens: List<String>, store: PresetStore, lookup: LookupAction) {
        lookup.execute(sender, nearby(sender, tokens, store))
    }

    private fun nearby(sender: CommandSender, tokens: List<String>, store: PresetStore): ParsedLookupArgs {
        val parsed = parsePresetted(sender, tokens, store)
        val now = System.currentTimeMillis()
        return parsed.copy(
            since = parsed.since ?: if (parsed.until == null) now - NEAR_WINDOW else null,
            scope = parsed.scope ?: if (parsed.world == null) LookupScope.Blocks(NEAR_RADIUS) else null,
        )
    }

    private fun parsePresetted(sender: CommandSender, tokens: List<String>, store: PresetStore) = parseWithPresets(
        tokens,
        (sender as? Player)?.uniqueId,
        store,
        System.currentTimeMillis(),
        liveLists(sender),
    )

    private fun tokens(line: String): List<String> =
        line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

private fun LiteralArgumentBuilder<CommandSourceStack>.takeBack(action: UndoAction) {
    executesCommand { ctx -> action.execute(ctx.source.sender) }
    literal("#confirm", "Take it back anyway, past the entity warning") {
        executesCommand { ctx -> action.execute(ctx.source.sender, confirmed = true) }
    }
}
