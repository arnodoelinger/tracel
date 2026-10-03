package com.tracel.plugin.setup

import com.tracel.storage.ports.ops.PurgeCategory

/** What the setup collected, one screen at a time. */
internal class SetupChoices {
    val keepMonths: MutableMap<PurgeCategory, Int> =
        PurgeCategory.entries.associateWithTo(mutableMapOf()) { FOREVER }

    var entityLimit: Int? = DEFAULT_ENTITY_LIMIT
    var radius: Int? = DEFAULT_RADIUS

    var blocks: Boolean = true
    var items: Boolean = true
    var entities: Boolean = true
    var events: Boolean = true
    var entityDamage: Boolean = true

    companion object {
        const val FOREVER = 0
        const val YEAR_MONTHS = 12
        const val DAYS_PER_MONTH = 30
        const val DAYS_PER_YEAR = 365
        const val DEFAULT_ENTITY_LIMIT = 256
        const val DEFAULT_RADIUS = 256
        const val LIMIT_STEP = 64
        const val MAX_LIMIT = 1024

        val KEEP_CHOICES: List<Int> = listOf(FOREVER, 1, 3, 6, 9, YEAR_MONTHS)

        fun days(months: Int): Int = if (months >= YEAR_MONTHS) DAYS_PER_YEAR else months * DAYS_PER_MONTH
    }
}
