package com.tracel.plugin.setup

import com.tracel.plugin.TracelServices
import com.tracel.plugin.i18n.tr
import com.tracel.plugin.importer.coreprotect.CoreProtectLocator
import com.tracel.storage.ports.ops.PurgeCategory
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.dialog.DialogResponseView
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.translation.GlobalTranslator
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

private const val WIDTH = 320
private const val BUTTON_WIDTH = 200
private const val ICON_WIDTH = 40

private val PURGE_KINDS: List<Triple<String, PurgeCategory, Material>> = listOf(
    Triple("blocks", PurgeCategory.BLOCKS, Material.GRASS_BLOCK),
    Triple("items", PurgeCategory.ITEMS, Material.CHEST),
    Triple("containers", PurgeCategory.CONTAINERS, Material.BARREL),
    Triple("events", PurgeCategory.EVENTS, Material.WRITABLE_BOOK),
)

/**
 * The welcome setup: a few `Paper` dialogs, one after the other, that ask how long to keep history, how careful
 * rollbacks should be and what to log.
 *
 * Their answers go into `config.toml` once the last question is answered. With `CoreProtect` on the server it
 * offers to bring its history over before saying goodbye.
 */
@Suppress("UnstableApiUsage")
internal class SetupWizard(private val services: TracelServices, private val state: SetupState) {
    /** Starts the setup for [player]. */
    fun open(player: Player) = show(player, welcome(player, SetupChoices()))

    private fun welcome(player: Player, choices: SetupChoices): Dialog = dialog(
        base(player, "setup.title", body(player, "setup.welcome.hello", "setup.welcome.intro")),
        DialogType.notice(button(player, "setup.button.continue") { _, p -> show(p, purge(p, choices)) }),
    )

    private fun purge(player: Player, choices: SetupChoices): Dialog = dialog(
        base(
            player,
            "setup.purge.title",
            body(player, "setup.purge.explain") +
                    PURGE_KINDS.map { (key, _, icon) -> entry(player, icon, "setup.purge.$key.about") },
            PURGE_KINDS.map { (key, category, _) ->
                choice(
                    player, "keep_$key", "setup.purge.$key.label",
                    SetupChoices.KEEP_CHOICES.map { it.toString() to "setup.purge.option.$it" },
                    choices.keepMonths.getValue(category).toString(),
                )
            },
        ),
        DialogType.notice(button(player, "setup.button.continue") { view, p ->
            for ((key, category, _) in PURGE_KINDS) {
                choices.keepMonths[category] = view.getText("keep_$key")?.toIntOrNull()
                    ?.takeIf { it in SetupChoices.KEEP_CHOICES }
                    ?: SetupChoices.FOREVER
            }
            show(p, entityLimit(p, choices))
        }),
    )

    private fun entityLimit(player: Player, choices: SetupChoices): Dialog = dialog(
        base(
            player,
            "setup.safety.title",
            body(player, "setup.safety.entities.explain"),
            listOf(
                slider(
                    player, "entities", "setup.safety.entities.label",
                    SetupChoices.LIMIT_STEP, SetupChoices.MAX_LIMIT, SetupChoices.LIMIT_STEP,
                    choices.entityLimit ?: SetupChoices.DEFAULT_ENTITY_LIMIT,
                ),
                check(player, "entities_none", "setup.safety.entities.none", choices.entityLimit == null),
            ),
        ),
        DialogType.notice(button(player, "setup.button.continue") { view, p ->
            choices.entityLimit = limit(view, "entities", "entities_none", SetupChoices.DEFAULT_ENTITY_LIMIT)
            show(p, radius(p, choices))
        }),
    )

    private fun radius(player: Player, choices: SetupChoices): Dialog = dialog(
        base(
            player,
            "setup.safety.title",
            body(player, "setup.safety.radius.explain"),
            listOf(
                slider(
                    player, "radius", "setup.safety.radius.label",
                    SetupChoices.LIMIT_STEP, SetupChoices.MAX_LIMIT, SetupChoices.LIMIT_STEP,
                    choices.radius ?: SetupChoices.DEFAULT_RADIUS,
                ),
                check(player, "radius_none", "setup.safety.radius.none", choices.radius == null),
            ),
        ),
        DialogType.notice(button(player, "setup.button.continue") { view, p ->
            choices.radius = limit(view, "radius", "radius_none", SetupChoices.DEFAULT_RADIUS)
            show(p, logging(p, choices))
        }),
    )

    private fun logging(player: Player, choices: SetupChoices): Dialog = dialog(
        base(
            player,
            "setup.logging.title",
            body(player, "setup.logging.explain"),
            listOf(
                check(player, "blocks", "setup.logging.blocks.label", choices.blocks),
                check(player, "items", "setup.logging.items.label", choices.items),
                check(player, "entities", "setup.logging.entities.label", choices.entities),
                check(player, "events", "setup.logging.events.label", choices.events),
                check(player, "damage", "setup.logging.damage.label", choices.entityDamage),
            ),
        ),
        DialogType.notice(button(player, "setup.button.continue") { view, p ->
            choices.blocks = view.getBoolean("blocks") ?: true
            choices.items = view.getBoolean("items") ?: true
            choices.entities = view.getBoolean("entities") ?: true
            choices.events = view.getBoolean("events") ?: true
            choices.entityDamage = view.getBoolean("damage") ?: true
            val restart = applyChoices(services, choices)
            state.finish()
            if (coreProtectFound()) show(p, migration(p, restart)) else show(p, goodbye(p, restart))
        }),
    )

    private fun migration(player: Player, restart: Boolean): Dialog = dialog(
        base(player, "setup.migrate.title", body(player, "setup.migrate.found", "setup.migrate.explain")),
        DialogType.confirmation(
            button(player, "setup.button.confirm") { _, p ->
                services.coreProtectImport.execute(p)
                show(p, goodbye(p, restart))
            },
            button(player, "setup.button.later") { _, p -> show(p, goodbye(p, restart)) },
        ),
    )

    private fun goodbye(player: Player, restart: Boolean): Dialog = dialog(
        base(
            player,
            "setup.title",
            body(player, *listOfNotNull("setup.done.config", "setup.done.thanks", "setup.done.restart".takeIf { restart }).toTypedArray()),
        ),
        DialogType.notice(button(player, "setup.button.done") { _, _ -> }),
    )

    private fun coreProtectFound(): Boolean {
        val plugins = services.plugin.dataFolder.toPath().toAbsolutePath().parent
        return runCatching { CoreProtectLocator.find(plugins) != null }.getOrDefault(false)
    }

    private fun show(player: Player, dialog: Dialog) {
        player.scheduler.run(services.plugin, { player.showDialog(dialog) }, null)
    }

    private fun dialog(base: DialogBase, type: DialogType): Dialog =
        Dialog.create { factory -> factory.empty().base(base).type(type) }

    private fun base(
        player: Player,
        title: String,
        body: List<DialogBody>,
        inputs: List<DialogInput> = emptyList(),
    ): DialogBase = DialogBase.builder(text(player, title))
        .canCloseWithEscape(true)
        .body(body)
        .inputs(inputs)
        .build()

    private fun body(player: Player, vararg keys: String): List<DialogBody> =
        keys.map { DialogBody.plainMessage(text(player, it), WIDTH) }

    @Suppress("SameParameterValue")
    private fun slider(
        player: Player,
        key: String,
        label: String,
        from: Int,
        to: Int,
        step: Int,
        initial: Int,
    ): DialogInput = DialogInput.numberRange(key, text(player, label), from.toFloat(), to.toFloat())
        .step(step.toFloat())
        .initial(initial.toFloat())
        .width(WIDTH)
        .build()

    private fun choice(
        player: Player,
        key: String,
        label: String,
        entries: List<Pair<String, String>>,
        selected: String,
    ): DialogInput = DialogInput.singleOption(
        key,
        text(player, label),
        entries.map { (id, name) ->
            SingleOptionDialogInput.OptionEntry.create(id, text(player, name), id == selected)
        },
    ).width(WIDTH).build()

    private fun entry(player: Player, icon: Material, key: String): DialogBody =
        DialogBody.item(ItemStack(icon))
            .description(DialogBody.plainMessage(text(player, key), WIDTH - ICON_WIDTH))
            .showTooltip(false)
            .build()

    private fun limit(view: DialogResponseView, slider: String, none: String, otherwise: Int): Int? =
        if (view.getBoolean(none) == true) null
        else view.getFloat(slider)?.toInt()?.coerceIn(SetupChoices.LIMIT_STEP, SetupChoices.MAX_LIMIT) ?: otherwise

    private fun check(player: Player, key: String, label: String, initial: Boolean): DialogInput =
        DialogInput.bool(key, text(player, label)).initial(initial).build()

    private fun button(
        player: Player,
        label: String,
        onClick: (DialogResponseView, Player) -> Unit,
    ): ActionButton = ActionButton.builder(text(player, label))
        .width(BUTTON_WIDTH)
        .action(
            DialogAction.customClick(
                { view, audience -> (audience as? Player)?.let { onClick(view, it) } },
                ClickCallback.Options.builder().build(),
            ),
        )
        .build()

    private fun text(player: Player, key: String): Component = GlobalTranslator.render(tr(key), player.locale())
}
