package com.tracel.plugin.config

import com.tracel.engine.store.StoreSettings
import com.tracel.plugin.governor.GovernorSettings

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
    val autoPurge: AutoPurgeSettings = AutoPurgeSettings(),
)
