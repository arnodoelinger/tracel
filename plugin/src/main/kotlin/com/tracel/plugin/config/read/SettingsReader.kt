package com.tracel.plugin.config.read

import com.tracel.api.access.Grant
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.StoreSettings
import com.tracel.plugin.command.args.time.TimeArgument
import com.tracel.plugin.config.*
import com.tracel.plugin.governor.GovernorSettings
import com.tracel.plugin.integration.privatebin.PrivateBin
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.net.URI

private val FOREVER = setOf("forever", "never", "off")

private val UNLIMITED = setOf("unlimited", "none", "off")

private const val NEVER = -1L

private const val NANOS_PER_MILLI = 1_000_000L

private const val TICK_MILLIS = 50L

/** `Tracel` settings. */
internal fun readSettings(
    advanced: TomlTable?,
    rollback: TomlTable? = null,
    complain: (String) -> Unit = {},
    paste: TomlTable? = null,
    purge: TomlTable? = null,
    logging: TomlTable? = null,
    updates: TomlTable? = null,
    api: TomlTable? = null,
): Settings {
    val defaults = StoreSettings()

    val sync = advanced.setting("advanced", "sync", defaults.sync, complain) { parseSync(it.toString()) }

    val memtable = advanced.setting("advanced", "memtable-size", defaults.memtableBytes, complain) {
        parseBytes(it.toString())?.takeIf { bytes -> bytes >= StoreSettings.MIN_MEMTABLE_BYTES }
    }

    val pending = advanced.setting("advanced", "max-pending-flushes", defaults.maxFrozenMemtables, complain) {
        (it as? Number)?.toInt()?.takeIf { count -> count >= StoreSettings.MIN_PENDING_FLUSHES }
    }

    val slots = advanced.setting("advanced", "capture-ring-slots", StoreSettings.DEFAULT_RING_SLOTS, complain) {
        (it as? Number)?.toInt()?.takeIf { n -> n >= MIN_RING_SLOTS && n.countOneBits() == 1 }
    }

    val entityRestoreLimit = rollback.setting(
        "rollback", "entity-restore-limit", DEFAULT_ENTITY_RESTORE_LIMIT, complain,
    ) {
        if (it.toString().trim().lowercase() in UNLIMITED) Int.MAX_VALUE
        else (it as? Number)?.toInt()?.takeIf { limit -> limit >= 0 }
    }

    val maxRadius = rollback.setting(
        "rollback", "max-radius", DEFAULT_ROLLBACK_MAX_RADIUS, complain,
    ) {
        if (it.toString().trim().lowercase() in UNLIMITED) Int.MAX_VALUE
        else (it as? Number)?.toInt()?.takeIf { radius -> radius > 0 }
    }.takeIf { it != Int.MAX_VALUE }

    val logEntityDamage = logging.setting(
        "logging", "entity-damage", DEFAULT_LOG_ENTITY_DAMAGE, complain,
    ) {
        it as? Boolean
    }

    val logBlocks = logging.setting("logging", "blocks", true, complain) { it as? Boolean }
    val logItems = logging.setting("logging", "items", true, complain) { it as? Boolean }
    val logEntities = logging.setting("logging", "entities", true, complain) { it as? Boolean }
    val logEvents = logging.setting("logging", "events", true, complain) { it as? Boolean }
    val logWorldEdit = logging.setting("logging", "worldedit", true, complain) { it as? Boolean }

    val governorDefaults = GovernorSettings()
    val minTickTime = rollback.setting(
        "rollback", "min-tick-time", governorDefaults.minNanos / NANOS_PER_MILLI, complain,
    ) {
        parseDuration(it.toString())?.takeIf { millis -> millis in 1..TICK_MILLIS }
    }
    val maxTickTime = rollback.setting(
        "rollback", "max-tick-time", governorDefaults.maxNanos / NANOS_PER_MILLI, complain,
    ) {
        parseDuration(it.toString())?.takeIf { millis -> millis in minTickTime..TICK_MILLIS }
    }

    val pasteUrl = paste.setting("paste", "paste-url", DEFAULT_PASTE_URL, complain) {
        it.toString().trim().takeIf { url -> runCatching { URI(url) }.getOrNull()?.scheme in setOf("http", "https") }
    }
    val pasteExpire = paste.setting("paste", "paste-expire", DEFAULT_PASTE_EXPIRE, complain) {
        it.toString().trim().takeIf { value -> PrivateBin.EXPIRE_NAME.matches(value) }
    }
    val pasteBurn = paste.setting("paste", "paste-burn", false, complain) { it as? Boolean }

    val checkUpdates = updates.setting("updates", "check", true, complain) { it as? Boolean }

    val autoPurge = purge.setting("purge", "auto-purge", false, complain) { it as? Boolean }
    val purgeInterval = purge.setting("purge", "interval", DEFAULT_PURGE_INTERVAL_MILLIS, complain) {
        TimeArgument.parseDuration(it.toString().trim().lowercase())
            ?.takeIf { millis -> millis >= MIN_PURGE_INTERVAL_MILLIS }
    }
    val keep = PurgeCategory.entries.associateWith { category ->
        purge.setting("purge", "keep-${category.name.lowercase()}", DEFAULT_PURGE_KEEP_MILLIS, complain) { raw ->
            val text = raw.toString().trim().lowercase()
            if (text in FOREVER) NEVER else TimeArgument.parseDuration(text)?.takeIf { millis -> millis > 0 }
        }.takeIf { it != NEVER }
    }

    return Settings(
        store = StoreSettings(
            memtableBytes = memtable,
            maxFrozenMemtables = pending,
            sync = sync,
            ringSlots = slots,
        ),
        entityRestoreLimit = entityRestoreLimit,
        logEntityDamage = logEntityDamage,
        rollbackMaxRadius = maxRadius,
        logging = LoggingSettings(logBlocks, logItems, logEntities, logEvents, logWorldEdit),
        governor = GovernorSettings(minNanos = minTickTime * NANOS_PER_MILLI, maxNanos = maxTickTime * NANOS_PER_MILLI),
        paste = PasteSettings(pasteUrl, pasteExpire, pasteBurn),
        updates = UpdateSettings(checkUpdates),
        autoPurge = AutoPurgeSettings(autoPurge, purgeInterval, keep),
        api = ApiSettings(readGrants(api, complain), readSlowpokes(api, complain)),
    )
}

private fun readSlowpokes(api: TomlTable?, complain: (String) -> Unit): Set<String> {
    val raw = api?.get("excuse-slowpokes") ?: return emptySet()
    val names = (raw as? TomlArray)?.toList() ?: run {
        complain("api.excuse-slowpokes: \"$raw\" is not a list of addon IDs; nobody is excused.")
        return emptySet()
    }
    return names.mapTo(HashSet()) { it.toString().lowercase() }
}

private fun readGrants(api: TomlTable?, complain: (String) -> Unit): Map<String, Set<Grant>> {
    val raw = api?.get("grants") ?: return emptyMap()
    val table = raw as? TomlTable ?: run {
        complain("api.grants: \"$raw\" is not a table of plugin names; every plugin keeps every grant.")
        return emptyMap()
    }
    val out = HashMap<String, Set<Grant>>()
    for (plugin in table.keySet()) {
        val names = (table.get(listOf(plugin)) as? TomlArray)?.toList() ?: run {
            complain("api.grants.$plugin: not a list of grants like [\"rollback\"]; it gets none.")
            continue
        }
        out[plugin.lowercase()] = names.mapNotNullTo(HashSet()) { name ->
            Grant.named(name.toString()) ?: run {
                val known = Grant.entries.joinToString { it.configName }
                complain("api.grants.$plugin: \"$name\" is not a grant; the grants are $known.")
                null
            }
        }
    }
    return out
}

private fun <T> TomlTable?.setting(
    section: String,
    key: String,
    default: T,
    complain: (String) -> Unit,
    parse: (Any) -> T?,
): T {
    val raw = this?.get(key) ?: return default
    parse(raw)?.let { return it }
    complain("$section.$key: \"$raw\" is not a value Tracel can use; keeping the default ($default).")
    return default
}
