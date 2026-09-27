package com.tracel.plugin.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.args.parseLookupArgs
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.suggest.ExportSuggest
import com.tracel.plugin.command.suggest.LookupSuggest
import com.tracel.plugin.command.suggest.RollbackSuggest
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.command.CommandSender

/** `Tracel` commands. */
object TracelCommand {
    /** Registers all `Tracel` commands. */
    fun register(registrar: Commands, services: TracelServices) {
        val undo = UndoAction(services)
        val rollback = RollbackAction(services)
        val lookup = LookupAction(services)
        val inspect = InspectAction(services)
        val purge = PurgeAction(services)
        val export = ExportAction(services)

        val root = literal("tracel") {
            executesCommand { ctx -> sendHelp(ctx.source.sender) }

            literal("rollback", "Roll back world and container changes") {
                requiresPermission("tracel.rollback")
                executesCommand { ctx -> RollbackPresenter.usage(ctx.source.sender) }
                literal("undo") { takeBack(undo) }
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(RollbackSuggest)
                    executesCommand { ctx ->
                        val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                        rollback.execute(ctx.source.sender, parseLookupArgs(tokens, System.currentTimeMillis()))
                    }
                }
            }

            literal("undo", "Take back the last rollback") {
                requiresPermission("tracel.rollback")
                takeBack(undo)
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
                        lookup.execute(ctx.source.sender, parseLookupArgs(tokens, System.currentTimeMillis()))
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
                literal("confirm") {
                    executesCommand { ctx -> purge.execute(ctx.source.sender) }
                }
            }

            literal("export", "Snapshot export and import") {
                requiresPermission("tracel.export")
                executesCommand { ctx -> export.executeExport(ctx.source.sender) }
                literal("import") {
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
                        literal("confirm") {
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
    }

    private fun sendHelp(sender: CommandSender) {
        sender.sendMessage("Tracel — forensics and history engine.")
        if (sender.hasPermission("tracel.rollback")) {
            sender.sendMessage(" /tracel rollback <flags> — roll back world and container changes")
            sender.sendMessage(" /tracel undo — take back the last rollback")
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

    private fun tokens(line: String): List<String> =
        line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

private fun LiteralArgumentBuilder<CommandSourceStack>.takeBack(action: UndoAction) {
    executesCommand { ctx -> action.execute(ctx.source.sender) }
    literal("#confirm") {
        executesCommand { ctx -> action.execute(ctx.source.sender, confirmed = true) }
    }
}
