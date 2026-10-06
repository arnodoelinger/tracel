package com.tracel.plugin.metrics

import com.tracel.engine.store.StoreReport
import com.tracel.engine.store.StoreSettings
import com.tracel.engine.store.StoreSync
import com.tracel.plugin.config.DEFAULT_PASTE_URL
import com.tracel.plugin.config.Settings
import com.tracel.plugin.integration.worldedit.WorldEditSupport
import com.tracel.plugin.services.TracelServices
import com.tracel.plugin.status.disk.DiskLevel
import java.util.concurrent.atomic.AtomicLong
import org.bstats.bukkit.Metrics
import org.bstats.charts.AdvancedPie
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart
import org.bstats.json.JsonObjectBuilder
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

/**
 * bStats` charts.
 *
 * How `Tracel` is set up, how big its history gets, and what goes wrong with it out there.
 */
internal object TracelMetrics {
    // https://bstats.org/plugin/bukkit/Tracel/34524
    private const val PLUGIN_ID: Int = 34524

    private const val STORE_FRESH_MILLIS = 60_000L
    private const val DAY = 86_400_000L
    private const val MIB = 1L shl 20
    private const val GIB = 1L shl 30

    @Volatile
    private var metrics: Metrics? = null

    /** Start Palantir. */
    fun start(plugin: JavaPlugin, services: TracelServices, settings: Settings) {
        JsonObjectBuilder().build()
        val metrics = Metrics(plugin, PLUGIN_ID)
        this.metrics = metrics
        setup(metrics, services, settings)
        state(metrics, services, settings)
        activity(metrics, services)
    }

    /** Stop Palantir. */
    fun stop() {
        metrics?.shutdown()
        metrics = null
    }

    private fun setup(metrics: Metrics, services: TracelServices, settings: Settings) {
        metrics.pie("sync_policy") {
            when (settings.store.sync) {
                StoreSync.EveryBatch -> "every-batch"
                is StoreSync.Interval -> "interval"
                StoreSync.Never -> "never"
            }
        }
        metrics.pie("advanced_tuning") {
            val defaults = StoreSettings()
            val untouched = settings.store.memtableBytes == defaults.memtableBytes &&
                    settings.store.maxFrozenMemtables == defaults.maxFrozenMemtables &&
                    settings.store.ringSlots == StoreSettings.DEFAULT_RING_SLOTS
            if (untouched) "defaults" else "tuned"
        }
        metrics.shares("logging_off") {
            val logging = settings.logging
            val off = buildList {
                if (!logging.blocks) add("blocks")
                if (!logging.items) add("items")
                if (!logging.entities) add("entities")
                if (!logging.events) add("events")
                if (!logging.worldEdit) add("worldedit")
                if (!settings.logEntityDamage) add("entity-damage")
            }
            off.ifEmpty { listOf("nothing") }.associateWith { 1 }
        }
        metrics.pie("auto_purge") {
            val purge = services.purgeSettings
            if (!purge.enabled) return@pie "off"
            purge.keep.values.filterNotNull().minOrNull()?.let(::keepBucket) ?: "keeps forever"
        }
        metrics.pie("max_radius") { settings.rollbackMaxRadius?.let(Telemetry::radiusBucket) ?: "unlimited" }
        metrics.pie("paste_host") { if (settings.paste.url == DEFAULT_PASTE_URL) "default" else "custom" }
        metrics.pie("world_edit") {
            val fawe = Bukkit.getPluginManager().isPluginEnabled(WorldEditSupport.FAWE)
            val any = fawe || Bukkit.getPluginManager().isPluginEnabled("WorldEdit")
            when {
                !any -> "none"
                services.worldEdit == null -> "not logged"
                fawe -> "fawe"
                else -> "worldedit"
            }
        }
        metrics.pie("server_support") { if (services.forwardCompatible) "newer than tested" else "tested" }
    }

    private fun state(metrics: Metrics, services: TracelServices, settings: Settings) {
        val store = StoreSample(services)
        metrics.pie("database_size") { sizeBucket(store.read().liveBytes) }
        metrics.pie("history_rows") { rowsBucket(store.read().rows) }
        metrics.pie("bytes_per_row") {
            val now = store.read()
            if (now.rows < 100_000) null else perRowBucket(now.liveBytes / now.rows)
        }
        metrics.pie("history_age") {
            store.read().oldestMillis?.let { ageBucket(System.currentTimeMillis() - it) } ?: "empty"
        }
        metrics.pie("disk_level") {
            val database = services.plugin.dataFolder.resolve("database")
            DiskLevel.of(database.usableSpace, database.totalSpace).name.lowercase()
        }
        metrics.pie("write_rate") { services.writeRate.perSecond()?.let(::rateBucket) }
        metrics.pie("capture_ring_peak") {
            fillBucket(Telemetry.drainPeakBacklog() * 100 / settings.store.ringSlots.coerceAtLeast(1))
        }
        metrics.pie("capture_losses") {
            val ring = services.store.capture
            when {
                ring.dropped > 0 -> "events lost"
                ring.ringFull > 0 -> "ring filled, nothing lost"
                else -> "none"
            }
        }
        metrics.line("events_logged", since { services.counters.seqIssued })
        metrics.line("events_lost", since { services.store.capture.dropped })
        metrics.shares("player_languages") {
            Bukkit.getOnlinePlayers().groupingBy { it.locale().language.ifEmpty { "unknown" } }.eachCount()
        }
    }

    private fun activity(metrics: Metrics, services: TracelServices) {
        val counted = listOf(
            Telemetry.COMMANDS, Telemetry.LOOKUP_FLAGS, Telemetry.ROLLBACK_FLAGS, Telemetry.ROLLBACK_RADIUS,
            Telemetry.ROLLBACK_WINDOW, Telemetry.ROLLBACK_OUTCOMES, Telemetry.ROLLBACK_SIZES, Telemetry.ROLLBACK_TIME,
            Telemetry.ROLLBACK_SPEED, Telemetry.UNDO_OUTCOMES, Telemetry.ERRORS, Telemetry.WARNINGS,
        )
        for (chart in counted) metrics.shares(chart) { Telemetry.drain(chart) }
        metrics.line("rollbacks_running") { services.composite.activeRollbacks.size }
    }

    private fun Metrics.pie(id: String, read: () -> String?) =
        addCustomChart(SimplePie(id) { runCatching(read).getOrNull() })

    private fun Metrics.shares(id: String, read: () -> Map<String, Int>) =
        addCustomChart(AdvancedPie(id) { runCatching(read).getOrDefault(emptyMap()) })

    private fun Metrics.line(id: String, read: () -> Int) =
        addCustomChart(SingleLineChart(id) { runCatching(read).getOrDefault(0) })

    private fun since(total: () -> Long): () -> Int {
        val last = AtomicLong(total())
        return {
            val now = total()
            (now - last.getAndSet(now)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private class StoreSample(private val services: TracelServices) {
        private var taken = 0L
        private var last: StoreReport? = null

        @Synchronized
        fun read(): StoreReport {
            val now = System.currentTimeMillis()
            last?.takeIf { now - taken < STORE_FRESH_MILLIS }?.let { return it }
            val fresh = services.store.report()
            taken = now
            last = fresh
            return fresh
        }
    }

    internal fun sizeBucket(bytes: Long): String = when {
        bytes < 100 * MIB -> "under 100 MiB"
        bytes < GIB -> "100 MiB - 1 GiB"
        bytes < 10 * GIB -> "1-10 GiB"
        bytes < 50 * GIB -> "10-50 GiB"
        else -> "50 GiB+"
    }

    internal fun rowsBucket(rows: Long): String = when {
        rows < 1_000_000 -> "under 1M"
        rows < 10_000_000 -> "1M-10M"
        rows < 100_000_000 -> "10M-100M"
        rows < 1_000_000_000 -> "100M-1B"
        else -> "1B+"
    }

    internal fun perRowBucket(bytes: Long): String = when {
        bytes < 100 -> "under 100 B"
        bytes < 200 -> "100-199 B"
        bytes < 300 -> "200-299 B"
        bytes < 500 -> "300-499 B"
        else -> "500 B+"
    }

    internal fun ageBucket(millis: Long): String = when {
        millis < 7 * DAY -> "under 7d"
        millis < 30 * DAY -> "7-30d"
        millis < 90 * DAY -> "30-90d"
        millis < 365 * DAY -> "90d-1y"
        else -> "1y+"
    }

    internal fun keepBucket(millis: Long): String = when {
        millis <= 7 * DAY -> "keeps 7d"
        millis <= 30 * DAY -> "keeps 30d"
        millis <= 90 * DAY -> "keeps 90d"
        millis <= 365 * DAY -> "keeps 1y"
        else -> "keeps longer"
    }

    internal fun rateBucket(perSecond: Double): String = when {
        perSecond < 10 -> "under 10/s"
        perSecond < 100 -> "10-100/s"
        perSecond < 1_000 -> "100-1k/s"
        perSecond < 10_000 -> "1k-10k/s"
        else -> "10k+/s"
    }

    internal fun fillBucket(percent: Long): String = when {
        percent < 1 -> "under 1%"
        percent < 10 -> "1-10%"
        percent < 50 -> "10-50%"
        percent < 90 -> "50-90%"
        percent < 100 -> "90-100%"
        else -> "overflowed"
    }
}
