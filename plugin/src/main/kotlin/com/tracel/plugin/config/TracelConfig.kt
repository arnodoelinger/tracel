package com.tracel.plugin.config

import com.tracel.api.access.Grant
import com.tracel.engine.store.PurgeCategory
import com.tracel.engine.store.StoreSettings
import com.tracel.plugin.services.monitoring.GovernorSettings
import com.tracel.plugin.util.MILLIS_PER_DAY
import com.tracel.plugin.util.MILLIS_PER_MINUTE

/**
 * `Tracel` settings.
 *
 * @see StoreSettings
 */
internal data class Settings(
    val store: StoreSettings = StoreSettings(),
    val entityRestoreLimit: Int = DEFAULT_ENTITY_RESTORE_LIMIT,
    val logEntityDamage: Boolean = DEFAULT_LOG_ENTITY_DAMAGE,
    val rollbackMaxRadius: Int? = DEFAULT_ROLLBACK_MAX_RADIUS,
    val logging: LoggingSettings = LoggingSettings(),
    val governor: GovernorSettings = GovernorSettings(),
    val paste: PasteSettings = PasteSettings(),
    val updates: UpdateSettings = UpdateSettings(),
    val autoPurge: AutoPurgeSettings = AutoPurgeSettings(),
    val api: ApiSettings = ApiSettings(),
)

const val DEFAULT_PURGE_INTERVAL_MILLIS = 90L * MILLIS_PER_DAY
const val DEFAULT_PURGE_KEEP_MILLIS = 90L * MILLIS_PER_DAY
const val MIN_PURGE_INTERVAL_MILLIS = 10L * MILLIS_PER_MINUTE
const val MIN_RING_SLOTS = 1024
const val DEFAULT_ENTITY_RESTORE_LIMIT = 256
const val DEFAULT_ROLLBACK_MAX_RADIUS = 256
const val DEFAULT_LOG_ENTITY_DAMAGE = true
const val DEFAULT_PASTE_URL = "https://privatebin.net"
const val DEFAULT_PASTE_EXPIRE = "3day"

/**
 * What other plugins may do through the API: [grants] by plugin, and whether any of them
 * may do what cannot be undone, purge and import ([allowDestructive]).
 */
data class ApiSettings(
    val grants: Map<String, Set<Grant>> = emptyMap(),
    val slowpokes: Set<String> = emptySet(),
    val allowDestructive: Boolean = false,
) {
    /** What [plugin] may do: everything, unless the admin listed it. */
    fun grantsOf(plugin: String): Set<Grant> = grants[plugin.lowercase()] ?: Grant.entries.toSet()

    /** Whether addon [id] keeps running when it stalls or hangs. */
    fun excuses(id: String): Boolean = id.lowercase() in slowpokes
}

/** The automatic purge: off unless asked, and how long each category of history is [keep]t. */
data class AutoPurgeSettings(
    val enabled: Boolean = false,
    val intervalMillis: Long = DEFAULT_PURGE_INTERVAL_MILLIS,
    val keep: Map<PurgeCategory, Long?> = PurgeCategory.entries.associateWith { DEFAULT_PURGE_KEEP_MILLIS },
)

/** What gets written to the history. Every kind is on unless it is turned off. */
data class LoggingSettings(
    val blocks: Boolean = true,
    val items: Boolean = true,
    val entities: Boolean = true,
    val events: Boolean = true,
    val worldEdit: Boolean = true,
)

/** Where a lookup export goes. */
data class PasteSettings(
    val url: String = DEFAULT_PASTE_URL,
    val expire: String = DEFAULT_PASTE_EXPIRE,
    val burn: Boolean = false,
)

/** How far a scope may reach. */
object ScopeLimits {
    const val MAX_BLOCK_RADIUS: Int = 1024
    const val MAX_CHUNK_RADIUS: Int = MAX_BLOCK_RADIUS shr 4

    @Volatile
    var rollbackMaxBlocks: Int? = MAX_BLOCK_RADIUS
}

/** Whether to look for a newer `Tracel` on `GitHub` and tell operators about it. */
data class UpdateSettings(val check: Boolean = true)
