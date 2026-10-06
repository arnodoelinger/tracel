package com.tracel.plugin.setup

import com.tracel.engine.store.PurgeCategory
import com.tracel.plugin.TracelPlugin
import com.tracel.plugin.command.args.scope.ScopeLimits
import com.tracel.plugin.config.AutoPurgeSettings
import com.tracel.plugin.config.LoggingSettings
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.startup.startAutoPurge

/** How often to purge, in milliseconds. 1 day. */
private const val PURGE_EVERY_MILLIS = 86_400_000L

/**
 * Writes [choices] into `config.toml` and applies what can change while the server runs: the purge, the
 * entity limit, and the rollback radius.
 *
 * What is logged takes a restart. Says whether it needs one.
 */
internal fun applyChoices(services: TracelServices, choices: SetupChoices): Boolean {
    val file = services.plugin.dataFolder.toPath().resolve("config.toml")

    val purging = choices.keepMonths.values.any { it > SetupChoices.FOREVER }
    ConfigEdit.set(file, "purge", "auto-purge", purging.toString())
    ConfigEdit.set(file, "purge", "interval", "\"1d\"")
    val keep = PurgeCategory.entries.associateWith { category ->
        choices.keepMonths.getValue(category).takeIf { it > SetupChoices.FOREVER }?.let { SetupChoices.days(it) }
    }
    for ((category, days) in keep) {
        ConfigEdit.set(file, "purge", "keep-${category.name.lowercase()}", days?.let { "\"${it}d\"" } ?: "\"forever\"")
    }
    ConfigEdit.set(file, "rollback", "entity-restore-limit", choices.entityLimit?.toString() ?: "\"unlimited\"")
    ConfigEdit.set(file, "rollback", "max-radius", choices.radius?.toString() ?: "\"unlimited\"")
    ConfigEdit.set(file, "logging", "blocks", choices.blocks.toString())
    ConfigEdit.set(file, "logging", "items", choices.items.toString())
    ConfigEdit.set(file, "logging", "entities", choices.entities.toString())
    ConfigEdit.set(file, "logging", "events", choices.events.toString())
    ConfigEdit.set(file, "logging", "entity-damage", choices.entityDamage.toString())

    services.entityRestoreLimit = choices.entityLimit ?: Int.MAX_VALUE
    ScopeLimits.rollbackMaxBlocks = choices.radius

    val purge = AutoPurgeSettings(
        enabled = purging,
        intervalMillis = PURGE_EVERY_MILLIS,
        keep = keep.mapValues { (_, days) -> days?.let { it * PURGE_EVERY_MILLIS } },
    )
    services.purgeSettings = purge
    services.autoPurge?.cancel()
    services.autoPurge = startAutoPurge(services.plugin as TracelPlugin, services, purge)

    val logging = LoggingSettings(
        choices.blocks, choices.items, choices.entities, choices.events,
        worldEdit = services.logging.worldEdit,
    )
    return logging != services.logging || choices.entityDamage != services.logEntityDamage
}
