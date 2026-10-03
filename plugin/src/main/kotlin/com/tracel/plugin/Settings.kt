package com.tracel.plugin

import com.tracel.plugin.command.args.ScopeLimits
import com.tracel.plugin.command.args.TimeArgument
import com.tracel.plugin.rollback.structure.GovernorSettings
import com.tracel.plugin.util.PrivateBin
import com.tracel.storage.TracelStorage
import com.tracel.storage.lsm.LsmConfig
import com.tracel.storage.lsm.write.SyncPolicy
import com.tracel.storage.ports.ops.PurgeCategory
import org.tomlj.TomlTable
import java.net.URI

/**
 * `Tracel` settings.
 *
 * @see LsmConfig
 */
internal data class Settings(
    val lsm: LsmConfig = LsmConfig(),
    val ringSlots: Int = TracelStorage.DEFAULT_RING_SLOTS,
    val entityRestoreLimit: Int = DEFAULT_ENTITY_RESTORE_LIMIT,
    val logEntityDamage: Boolean = DEFAULT_LOG_ENTITY_DAMAGE,
    val rollbackMaxRadius: Int? = ScopeLimits.MAX_BLOCK_RADIUS,
    val logging: LoggingSettings = LoggingSettings(),
    val governor: GovernorSettings = GovernorSettings(),
    val paste: PasteSettings = PasteSettings(),
    val autoPurge: AutoPurgeSettings = AutoPurgeSettings(),
)

/** What gets written to the history. Every kind is on unless it is turned off. */
data class LoggingSettings(
    val blocks: Boolean = true,
    val items: Boolean = true,
    val entities: Boolean = true,
    val events: Boolean = true,
)

/** Where a lookup export goes. */
data class PasteSettings(
    val url: String = DEFAULT_PASTE_URL,
    val expire: String = DEFAULT_PASTE_EXPIRE,
    val burn: Boolean = false,
)

/** The automatic purge: off unless asked, and how long each category of history is [keep]t. */
data class AutoPurgeSettings(
    val enabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_PURGE_INTERVAL_MILLIS,
    val keep: Map<PurgeCategory, Long?> = PurgeCategory.entries.associateWith { DEFAULT_PURGE_KEEP_MILLIS },
)

const val DEFAULT_PURGE_INTERVAL_MILLIS = 90L * 86_400_000
const val DEFAULT_PURGE_KEEP_MILLIS = 90L * 86_400_000
const val MIN_PURGE_INTERVAL_MILLIS = 10L * 60_000

private val FOREVER = setOf("forever", "never", "off")
private val UNLIMITED = setOf("unlimited", "none", "off")
private const val NEVER = -1L

const val MIN_RING_SLOTS = 1024
const val DEFAULT_ENTITY_RESTORE_LIMIT = 128
const val DEFAULT_LOG_ENTITY_DAMAGE = true
const val DEFAULT_PASTE_URL = "https://privatebin.net"
const val DEFAULT_PASTE_EXPIRE = "3day"

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
): Settings {
    val defaults = LsmConfig()

    val sync = advanced.setting("advanced", "sync", defaults.sync, complain) { parseSync(it.toString()) }

    val memtable = advanced.setting("advanced", "memtable-size", defaults.memtableBytes, complain) {
        parseBytes(it.toString())?.takeIf { bytes -> bytes >= LsmConfig.MIN_MEMTABLE_BYTES }
    }

    val pending = advanced.setting("advanced", "max-pending-flushes", defaults.maxFrozenMemtables, complain) {
        (it as? Number)?.toInt()?.takeIf { count -> count >= LsmConfig.MIN_PENDING_FLUSHES }
    }

    val slots = advanced.setting("advanced", "capture-ring-slots", TracelStorage.DEFAULT_RING_SLOTS, complain) {
        (it as? Number)?.toInt()?.takeIf { n -> n >= MIN_RING_SLOTS && n.countOneBits() == 1 }
    }

    val entityRestoreLimit = rollback.setting(
        "rollback", "entity-restore-limit", DEFAULT_ENTITY_RESTORE_LIMIT, complain,
    ) {
        if (it.toString().trim().lowercase() in UNLIMITED) Int.MAX_VALUE
        else (it as? Number)?.toInt()?.takeIf { limit -> limit >= 0 }
    }

    val maxRadius = rollback.setting(
        "rollback", "max-radius", ScopeLimits.MAX_BLOCK_RADIUS, complain,
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
        lsm = defaults.copy(
            sync = sync,
            memtableBytes = memtable,
            maxFrozenMemtables = pending,
        ),
        ringSlots = slots,
        entityRestoreLimit = entityRestoreLimit,
        logEntityDamage = logEntityDamage,
        rollbackMaxRadius = maxRadius,
        logging = LoggingSettings(logBlocks, logItems, logEntities, logEvents),
        governor = GovernorSettings(minNanos = minTickTime * NANOS_PER_MILLI, maxNanos = maxTickTime * NANOS_PER_MILLI),
        paste = PasteSettings(pasteUrl, pasteExpire, pasteBurn),
        autoPurge = AutoPurgeSettings(autoPurge, purgeInterval, keep),
    )
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
    complain("$section.$key: \"$raw\" is not a value Tracel can use; keeping the default ($default)")
    return default
}

private fun parseSync(value: String): SyncPolicy? =
    when (value.trim().lowercase()) {
        "every-batch" -> SyncPolicy.EveryBatch
        "never" -> SyncPolicy.Never
        else -> parseDuration(value)?.let(SyncPolicy::Interval)
    }

private fun parseDuration(value: String): Long? {
    val text = value.trim().lowercase()

    val multiplier = when {
        text.endsWith("ms") -> 1L
        text.endsWith("s") -> 1_000L
        text.endsWith("m") -> 60_000L
        else -> return null
    }

    val number = text.removeSuffix("ms")
        .removeSuffix("s")
        .removeSuffix("m")
        .toLongOrNull()
        ?: return null

    return number.takeIf { it > 0 }?.times(multiplier)
}

private fun parseBytes(value: String): Long? {
    val text = value.trim().lowercase()

    val multiplier = when {
        text.endsWith("kib") || text.endsWith("kb") -> 1L shl 10
        text.endsWith("mib") || text.endsWith("mb") -> 1L shl 20
        text.endsWith("gib") || text.endsWith("gb") -> 1L shl 30
        text.endsWith("b") -> 1L
        else -> 1L
    }

    val number = text
        .removeSuffix("kib")
        .removeSuffix("kb")
        .removeSuffix("mib")
        .removeSuffix("mb")
        .removeSuffix("gib")
        .removeSuffix("gb")
        .removeSuffix("b")
        .trim()
        .toLongOrNull()
        ?: return null

    return number * multiplier
}

