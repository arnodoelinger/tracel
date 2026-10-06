package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.permission.has
import com.tracel.plugin.command.suggest.ExportSuggest
import com.tracel.plugin.command.suggest.PurgeSuggest
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.usage
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel data`: export, import, migrate, purge. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.dataBranch(actions: CommandActions) {
    val export = actions.export
    val import = actions.import
    val coreProtect = actions.coreProtect
    val purge = actions.purge
    val services = actions.services
    literal("data", tr("command.data")) {
        requires { it.sender.has(Permission.EXPORT) || it.sender.has(Permission.PURGE) }
        executesCommand { ctx -> ctx.source.sender.usage("data") }

        literal("export", tr("command.export")) {
            requiresPermission(Permission.EXPORT)
            executesCommand { ctx -> export.confirmExport(ctx.source.sender) }
            literal("#confirm", tr("command.export_confirm")) {
                executesCommand { ctx -> export.executeExport(ctx.source.sender) }
            }
            literal("#stop", tr("command.export_stop")) {
                executesCommand { ctx -> export.stop(ctx.source.sender) }
            }
        }

        literal("import", tr("command.import")) {
            requiresPermission(Permission.EXPORT)
            executesCommand { ctx -> ctx.source.sender.usage("data import") }
            literal("#stop", tr("command.import_stop")) {
                executesCommand { ctx -> import.stop(ctx.source.sender) }
            }
            argument("file", StringArgumentType.string()) {
                suggests(ExportSuggest.suggesting(services.exportDirectory))
                executesCommand { ctx ->
                    import.preview(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "file")
                    )
                }
                literal("#confirm", tr("command.import_confirm")) {
                    executesCommand { ctx ->
                        val file = StringArgumentType.getString(ctx, "file")
                        import.execute(ctx.source.sender, file)
                    }
                }
            }
        }

        literal("migrate", tr("command.migrate")) {
            requiresPermission(Permission.EXPORT)
            executesCommand { ctx -> ctx.source.sender.usage("data migrate") }
            literal("coreprotect", tr("command.migrate_coreprotect")) {
                executesCommand { ctx -> coreProtect.preview(ctx.source.sender) }
                literal("#confirm", tr("command.migrate_coreprotect_confirm")) {
                    executesCommand { ctx -> coreProtect.execute(ctx.source.sender) }
                }
                literal("#stop", tr("command.migrate_coreprotect_stop")) {
                    executesCommand { ctx -> coreProtect.stop(ctx.source.sender) }
                }
            }
        }

        literal("purge", tr("command.purge")) {
            requiresPermission(Permission.PURGE)
            executesCommand { ctx -> purge.execute(ctx.source.sender, emptyList()) }
            argument("filters", StringArgumentType.greedyString()) {
                suggests(PurgeSuggest)
                executesCommand { ctx ->
                    purge.execute(ctx.source.sender, tokens(StringArgumentType.getString(ctx, "filters")))
                }
            }
        }
    }
}
