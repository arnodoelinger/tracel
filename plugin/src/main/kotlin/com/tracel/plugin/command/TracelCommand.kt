package com.tracel.plugin.command

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.tracel.plugin.TracelServices
import com.tracel.plugin.command.action.*
import com.tracel.plugin.command.action.support.NothingWeCanDo
import com.tracel.plugin.command.args.LookupScope
import com.tracel.plugin.command.args.ParsedLookupArgs
import com.tracel.plugin.command.highlight.Highlights
import com.tracel.plugin.command.presenter.LookupPresenter
import com.tracel.plugin.command.presenter.RollbackPresenter
import com.tracel.plugin.command.preset.PresetStore
import com.tracel.plugin.command.preset.Presets
import com.tracel.plugin.command.preset.parseWithPresets
import com.tracel.plugin.command.suggest.*
import com.tracel.plugin.i18n.*
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.*

/** `Tracel` commands. */
object TracelCommand {
    private const val NEAR_WINDOW = 15 * 60_000L
    private const val NEAR_RADIUS = 16

    private val HELP = listOf(
        "tracel.lookup" to "lookup",
        "tracel.inspect" to "inspect",
        "tracel.rollback" to "rollback",
        "tracel.rollback" to "undo",
        "tracel.lookup" to "near",
        "tracel.lookup" to "player",
        "tracel.preset" to "preset",
        "tracel.export" to "data",
        "tracel.purge" to "purge",
    )

    /** Registers all `Tracel` commands. */
    fun register(registrar: Commands, services: TracelServices) {
        val nothing = NothingWeCanDo(services)
        val undo = UndoAction(services, nothing)
        val highlights = Highlights(services.plugin)
        val rollback = RollbackAction(services, highlights)
        val lookup = services.lookup
        val inspect = InspectAction(services)
        val purge = PurgeAction(services)
        val export = ExportAction(services)
        val import = ImportAction(services)
        val coreProtect = CoreProtectImportAction(services)
        val store = PresetStore(services.plugin.dataFolder.toPath().resolve("presets.toml"))
        Presets.store = store
        val presets = PresetAction(store, nothing)
        val player = PlayerAction(services)

        val root = literal("tracel") {
            executesCommand { ctx -> sendHelp(ctx.source.sender, services.plugin) }

            literal("help", tr("command.help")) {
                executesCommand { ctx -> sendHelp(ctx.source.sender, services.plugin) }
            }

            literal("rollback", tr("command.rollback")) {
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

            literal("undo", tr("command.undo")) {
                requiresPermission("tracel.rollback")
                takeBack(undo)
            }

            literal("lookup", tr("command.lookup")) {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> LookupPresenter.usage(ctx.source.sender) }
                argument("flags", StringArgumentType.greedyString()) {
                    suggests(LookupSuggest)
                    executesCommand { ctx ->
                        val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                        if ("#export" in tokens) return@executesCommand lookup.export(ctx.source.sender)
                        if ("#refresh" in tokens) return@executesCommand refresh(
                            ctx.source.sender,
                            tokens,
                            store,
                            lookup
                        )
                        val turned = tokens.singleOrNull()?.takeIf { it.startsWith("p:") || it.startsWith("page:") }
                            ?.substringAfter(':')?.toIntOrNull()
                        if (turned != null && turned >= 1) lookup.turn(ctx.source.sender, turned)
                        else lookup.execute(
                            ctx.source.sender,
                            parsePresetted(ctx.source.sender, tokens, store).rerunnable("lookup", tokens)
                        )
                    }
                }
            }

            literal("tp", tr("command.tp")) {
                requiresPermission("tracel.lookup")
                argument("world", StringArgumentType.word()) {
                    argument("x", IntegerArgumentType.integer()) {
                        argument("y", IntegerArgumentType.integer()) {
                            argument("z", IntegerArgumentType.integer()) {
                                executesCommand { ctx -> teleport(ctx) }
                            }
                        }
                    }
                }
            }

            literal("preset", tr("command.preset")) {
                requiresPermission("tracel.preset")
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

            literal("near", tr("command.near")) {
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

            literal("player", tr("command.player")) {
                requiresPermission("tracel.lookup")
                executesCommand { ctx -> ctx.source.sender.usage("player") }
                argument("player", StringArgumentType.word()) {
                    suggests(PlayerNameSuggest)
                    executesCommand { ctx ->
                        player.execute(
                            ctx.source.sender,
                            StringArgumentType.getString(ctx, "player"),
                            ParsedLookupArgs()
                        )
                    }
                    argument("flags", StringArgumentType.greedyString()) {
                        suggests(LookupSuggest)
                        executesCommand { ctx ->
                            val tokens = tokens(StringArgumentType.getString(ctx, "flags"))
                            player.execute(
                                ctx.source.sender,
                                StringArgumentType.getString(ctx, "player"),
                                parsePresetted(ctx.source.sender, tokens, store),
                            )
                        }
                    }
                }
            }

            literal("inspect", tr("command.inspect")) {
                requiresPermission("tracel.inspect")
                executesCommand { ctx -> inspect.execute(ctx.source.sender) }
            }

            literal("data", tr("command.data")) {
                requiresPermission("tracel.export")
                executesCommand { ctx -> ctx.source.sender.usage("data") }

                literal("export", tr("command.export")) {
                    executesCommand { ctx -> export.confirmExport(ctx.source.sender) }
                    literal("#confirm", tr("command.export_confirm")) {
                        executesCommand { ctx -> export.executeExport(ctx.source.sender) }
                    }
                    literal("#stop", tr("command.export_stop")) {
                        executesCommand { ctx -> export.stop(ctx.source.sender) }
                    }
                }

                literal("import", tr("command.import")) {
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
            }

            literal("purge", tr("command.purge")) {
                requiresPermission("tracel.purge")
                executesCommand { ctx -> purge.execute(ctx.source.sender, emptyList()) }
                argument("filters", StringArgumentType.greedyString()) {
                    suggests(PurgeSuggest)
                    executesCommand { ctx ->
                        purge.execute(ctx.source.sender, tokens(StringArgumentType.getString(ctx, "filters")))
                    }
                }
            }
        }

        registrar.register(root.build(), "Tracel commands.", listOf("tr"))
        registrar.answerSuggestionsFromServer("tracel", "tr")
    }

    private fun sendHelp(sender: CommandSender, plugin: Plugin) {
        val meta = plugin.pluginMeta
        val lines = mutableListOf(
            tr("help.title", "version" to meta.version),
            tr("help.about", "author" to meta.authors.joinToString(", ")),
            Component.empty(),
        )
        for ((permission, key) in HELP) {
            if (sender.hasPermission(permission)) lines += tr("help.$key")
        }
        lines += tr("help.help")
        sender.say(Component.join(JoinConfiguration.newlines(), lines))
    }

    private fun teleport(ctx: CommandContext<CommandSourceStack>) {
        val player = ctx.source.sender as? Player ?: return
        val world =
            runCatching { Bukkit.getWorld(UUID.fromString(StringArgumentType.getString(ctx, "world"))) }.getOrNull()
        if (world == null) return player.failed("tp.failed", tr("tp.reason.no_world"), tr("tp.hint.no_world"))
        val at = Location(
            world,
            IntegerArgumentType.getInteger(ctx, "x") + 0.5,
            IntegerArgumentType.getInteger(ctx, "y") + 1.0,
            IntegerArgumentType.getInteger(ctx, "z") + 0.5,
            player.location.yaw,
            player.location.pitch,
        )
        player.teleportAsync(at)
    }

    private fun refresh(sender: CommandSender, flags: List<String>, store: PresetStore, lookup: LookupAction) {
        val (command, anchor) = lookup.last(sender) ?: return sender.send("lookup.no_search")
        val page =
            flags.firstNotNullOfOrNull { it.removePrefix("p:").toIntOrNull()?.takeIf { _ -> it.startsWith("p:") } } ?: 1
        val words = tokens(command)
        val tokens = words.drop(1)
        val parsed =
            if (words.first() == "near") nearby(sender, tokens, store) else parsePresetted(sender, tokens, store)
        lookup.execute(sender, parsed.rerunnable(words.first(), tokens).copy(page = page, anchor = anchor))
    }

    private fun runNear(sender: CommandSender, tokens: List<String>, store: PresetStore, lookup: LookupAction) {
        lookup.execute(sender, nearby(sender, tokens, store).rerunnable("near", tokens))
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

    private fun ParsedLookupArgs.rerunnable(name: String, tokens: List<String>) =
        copy(
            command = (listOf(name) + tokens.filterNot { it.startsWith("page:") || it.startsWith("p:") }).joinToString(
                " "
            )
        )

    private fun tokens(line: String): List<String> =
        line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

private fun LiteralArgumentBuilder<CommandSourceStack>.takeBack(action: UndoAction) {
    executesCommand { ctx -> action.execute(ctx.source.sender) }
    literal("#confirm", tr("command.undo_confirm")) {
        executesCommand { ctx -> action.execute(ctx.source.sender, confirmed = true) }
    }
    argument("job", LongArgumentType.longArg(1)) {
        executesCommand { ctx -> action.execute(ctx.source.sender, job = LongArgumentType.getLong(ctx, "job")) }
        literal("#confirm", tr("command.undo_confirm")) {
            executesCommand { ctx ->
                action.execute(ctx.source.sender, confirmed = true, job = LongArgumentType.getLong(ctx, "job"))
            }
        }
    }
}
