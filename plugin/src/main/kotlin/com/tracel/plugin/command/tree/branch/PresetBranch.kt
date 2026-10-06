package com.tracel.plugin.command.tree.branch

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.tracel.plugin.command.brigadier.argument
import com.tracel.plugin.command.brigadier.executesCommand
import com.tracel.plugin.command.brigadier.literal
import com.tracel.plugin.command.brigadier.requiresPermission
import com.tracel.plugin.command.permission.Permission
import com.tracel.plugin.command.suggest.preset.PresetAddFlagsSuggest
import com.tracel.plugin.command.suggest.preset.PresetAddNameSuggest
import com.tracel.plugin.command.suggest.preset.PresetNameSuggest
import com.tracel.plugin.command.suggest.preset.PresetOwnedSuggest
import com.tracel.plugin.command.tree.CommandActions
import com.tracel.plugin.command.tree.tokens
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.i18n.usage
import io.papermc.paper.command.brigadier.CommandSourceStack

/** `/tracel preset`: saved sets of flags. */
internal fun LiteralArgumentBuilder<CommandSourceStack>.presetBranch(actions: CommandActions) {
    val presets = actions.presets
    literal("preset", tr("command.preset")) {
        requiresPermission(Permission.PRESET)
        executesCommand { ctx -> ctx.source.sender.usage("preset") }
        literal("list", tr("command.preset_list")) {
            executesCommand { ctx -> presets.list(ctx.source.sender) }
        }
        literal("add", tr("command.preset_add")) {
            executesCommand { ctx ->
                ctx.source.sender.usage("preset add")
            }
            argument("name", StringArgumentType.word()) {
                suggests(PresetAddNameSuggest)
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(PresetAddFlagsSuggest)
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
        literal("show", tr("command.preset_show")) {
            executesCommand { ctx -> ctx.source.sender.usage("preset show") }
            argument("name", StringArgumentType.word()) {
                suggests(PresetNameSuggest)
                executesCommand { ctx ->
                    presets.show(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "name")
                    )
                }
            }
        }
        literal("share", tr("command.preset_share")) {
            executesCommand { ctx -> ctx.source.sender.usage("preset share") }
            argument("name", StringArgumentType.word()) {
                suggests(PresetOwnedSuggest(server = false))
                executesCommand { ctx ->
                    presets.share(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "name")
                    )
                }
            }
        }
        literal("unshare", tr("command.preset_unshare")) {
            executesCommand { ctx -> ctx.source.sender.usage("preset unshare") }
            argument("name", StringArgumentType.word()) {
                suggests(PresetOwnedSuggest(server = true))
                executesCommand { ctx ->
                    presets.unshare(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "name")
                    )
                }
            }
        }
        literal("delete", tr("command.preset_delete")) {
            executesCommand { ctx -> ctx.source.sender.usage("preset delete") }
            argument("name", StringArgumentType.word()) {
                suggests(PresetNameSuggest)
                executesCommand { ctx ->
                    presets.delete(
                        ctx.source.sender,
                        StringArgumentType.getString(ctx, "name")
                    )
                }
            }
        }
    }
}
