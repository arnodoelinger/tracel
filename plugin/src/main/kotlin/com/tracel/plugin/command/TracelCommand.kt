package com.tracel.plugin.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.ExportAction
import com.tracel.plugin.command.action.InspectAction
import com.tracel.plugin.command.action.LookupAction
import com.tracel.plugin.command.action.PurgeAction
import com.tracel.plugin.command.action.RollbackAction
import com.tracel.plugin.command.action.UndoAction
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.suggest.LookupSuggest
import com.tracel.plugin.command.args.parseLookupArgs
import io.papermc.paper.command.brigadier.Commands

/** `Tracel` commands. */
object TracelCommand {
    /** Registers all `Tracel` commands. */
    fun register(registrar: Commands, services: TracelServices) {
        val undoAction = UndoAction(services)
        val rollbackAction = RollbackAction(services)
        val lookupAction = LookupAction(services)
        val inspectAction = InspectAction(services)
        val purgeAction = PurgeAction(services)
        val exportAction = ExportAction(services)

        val root = literal("tracel") {
            executesCommand { ctx ->
                val sender = ctx.source.sender
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

            // Rollback
            literal("rollback") {
                requiresPermission("tracel.rollback")
                executesCommand { ctx -> RollbackPresenter.usage(ctx.source.sender) }

                literal("undo") {
                    executesCommand { ctx -> undoAction.execute(ctx.source.sender) }
                    argument("confirm", StringArgumentType.greedyString()) {
                        executesCommand { ctx ->
                            val sender = ctx.source.sender
                            if (StringArgumentType.getString(ctx, "confirm").trim().lowercase() == "#confirm") {
                                undoAction.execute(sender, confirmed = true)
                            } else {
                                sender.sendMessage("Undo: takes nothing but #confirm — it always undoes the most recent rollback.")
                                RollbackPresenter.undoUsage(sender)
                            }
                        }
                    }
                }

                argument("flags", StringArgumentType.greedyString()) {
                    suggests(LookupSuggest)
                    executesCommand { ctx ->
                        val input = StringArgumentType.getString(ctx, "flags")
                        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        val sender = ctx.source.sender

                        if (tokens.firstOrNull()?.lowercase() == "undo") {
                            val rest = tokens.drop(1)
                            val confirmed = rest.singleOrNull()?.lowercase() == "#confirm"
                            if (rest.isNotEmpty() && !confirmed) {
                                sender.sendMessage("Undo: takes nothing but #confirm — it always undoes the most recent rollback.")
                                RollbackPresenter.undoUsage(sender)
                                return@executesCommand
                            }
                            undoAction.execute(sender, confirmed)
                            return@executesCommand
                        }

                        val parsed = parseLookupArgs(tokens, System.currentTimeMillis())
                        rollbackAction.execute(sender, parsed)
                    }
                }
            }

            // Undo
            literal("undo") {
                requiresPermission("tracel.rollback")
                executesCommand { ctx -> undoAction.execute(ctx.source.sender) }
                argument("flags", StringArgumentType.greedyString()) {
                    executesCommand { ctx ->
                        val sender = ctx.source.sender
                        if (StringArgumentType.getString(ctx, "flags").trim().lowercase() == "#confirm") {
                            undoAction.execute(sender, confirmed = true)
                        } else {
                            sender.sendMessage("Undo: takes nothing but #confirm — it always undoes the most recent rollback.")
                            RollbackPresenter.undoUsage(sender)
                        }
                    }
                }
            }

            // Restore
            literal("restore") {
                requiresPermission("tracel.rollback")
                executesCommand { ctx -> undoAction.execute(ctx.source.sender) }
            }

            // Lookup
            literal("lookup") {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> LookupPresenter.usage(ctx.source.sender) }

                argument("flags", StringArgumentType.greedyString()) {
                    suggests(LookupSuggest)
                    executesCommand { ctx ->
                        val input = StringArgumentType.getString(ctx, "flags")
                        val tokens = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        val parsed = parseLookupArgs(tokens, System.currentTimeMillis())
                        lookupAction.execute(ctx.source.sender, parsed)
                    }
                }
            }

            // Inspect
            literal("inspect") {
                requiresPermission("tracel.inspect")
                executesCommand { ctx -> inspectAction.execute(ctx.source.sender) }
            }

            // Purge
            literal("purge") {
                requiresPermission("tracel.purge")
                executesCommand { ctx ->
                    ctx.source.sender.sendMessage("Usage: /tracel purge confirm — wipes the entire Tracel database, with no undo.")
                }

                literal("confirm") {
                    executesCommand { ctx -> purgeAction.execute(ctx.source.sender) }
                }
            }

            // Export
            literal("export") {
                requiresPermission("tracel.export")
                executesCommand { ctx -> exportAction.executeExport(ctx.source.sender) }

                literal("import") {
                    executesCommand { ctx ->
                        ctx.source.sender.sendMessage("Usage: /tracel export import <file> confirm — replaces the entire history with that file.")
                    }

                    argument("file", StringArgumentType.string()) {
                        suggests { _, builder ->
                            val files = LookupSuggest.suggestExportFiles(services.exportDirectory, builder.remaining)
                            files.forEach(builder::suggest)
                            builder.buildFuture()
                        }
                        executesCommand { ctx ->
                            val file = StringArgumentType.getString(ctx, "file")
                            ctx.source.sender.sendMessage("Usage: /tracel export import $file confirm — confirm is required to proceed.")
                        }

                        literal("confirm") {
                            executesCommand { ctx ->
                                val file = StringArgumentType.getString(ctx, "file")
                                exportAction.executeImport(ctx.source.sender, file)
                            }
                        }
                    }
                }
            }
        }

        registrar.register(root.build(), "Tracel commands.", listOf("tr"))
    }
}
