package com.tracel.plugin.services

import com.tracel.engine.capture.CaptureGate
import com.tracel.engine.rollback.involution.InvolutionJobCoordinator
import com.tracel.engine.rollback.job.RollbackJobCoordinator
import com.tracel.engine.rollback.journal.Journal
import com.tracel.engine.rollback.outside.OutsideSteps
import com.tracel.platform.scheduler.TracelSchedulers
import com.tracel.platform.storage.UnitOfWork
import com.tracel.plugin.api.CommandApi
import com.tracel.plugin.api.rollback.Borrows
import com.tracel.plugin.bukkit.rollback.structure.rescue.rescueJoined
import com.tracel.plugin.command.action.CoreProtectImportAction
import com.tracel.plugin.command.action.LookupAction
import com.tracel.plugin.command.action.support.PurgeGate
import com.tracel.plugin.command.scrub.Scrubs
import com.tracel.plugin.config.ApiSettings
import com.tracel.plugin.config.AutoPurgeSettings
import com.tracel.plugin.config.DEFAULT_ENTITY_RESTORE_LIMIT
import com.tracel.plugin.config.DEFAULT_LOG_ENTITY_DAMAGE
import com.tracel.plugin.config.LoggingSettings
import com.tracel.plugin.config.PasteSettings
import com.tracel.plugin.listener.support.drop.BlockReleaseQueue
import com.tracel.plugin.services.monitoring.GovernorSettings
import com.tracel.plugin.services.monitoring.TickGovernor
import com.tracel.plugin.services.monitoring.WriteRate
import com.tracel.plugin.services.specifics.ListenerState
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Shared services used by `Tracel` listeners and commands.
 *
 * @param plugin plugin instance
 * @param storage everything the engine keeps on disk
 * @param schedulers provides plugin's schedulers
 * @param scope scope for plugin's background work
 * @param rollback coordinates rollback jobs
 * @param undo coordinates involution jobs
 * @param undoJournal which undo steps already ran, plus whether their items went back
 * @param outside what addons were told about their records a rollback took back
 * @param gate fast path for capture events
 * @param exportDirectory directory for history exports and imports
 * @param entityRestoreLimit how many entities one rollback may bring back before it asks
 * @param governorSettings how long a rollback may hold one region tick, at least and at most
 * @param logEntityDamage whether a blow dealt to a mob is written down: as a hit, and as a change a rollback can undo
 * @param paste where a lookup export goes
 * @param forwardCompatible server Minecraft is newer than the newest tested release
 * @param logging which kinds of history are written
 * @param api what other plugins may do through the API
 */
class TracelServices(
    val plugin: Plugin,
    val storage: StorageService,
    val schedulers: TracelSchedulers,
    val scope: CoroutineScope,
    val rollback: RollbackJobCoordinator,
    val undo: InvolutionJobCoordinator,
    val undoJournal: Journal,
    val gate: CaptureGate,
    val outside: OutsideSteps,
    val exportDirectory: Path,
    val logEntityDamage: Boolean = DEFAULT_LOG_ENTITY_DAMAGE,
    val paste: PasteSettings = PasteSettings(),
    val forwardCompatible: Boolean = false,
    val logging: LoggingSettings = LoggingSettings(),
    val api: ApiSettings = ApiSettings(),

    governorSettings: GovernorSettings = GovernorSettings(),

    @Volatile
    var entityRestoreLimit: Int = DEFAULT_ENTITY_RESTORE_LIMIT,

    @Volatile
    var autoPurge: Job? = null,

    @Volatile
    var worldEdit: AutoCloseable? = null,

    @Volatile
    var luckPerms: AutoCloseable? = null
) : UnitOfWork by storage.unit {
    val purging: AtomicBoolean = AtomicBoolean(false)

    @Volatile
    var purgeSettings: AutoPurgeSettings = AutoPurgeSettings()

    @Volatile
    var lastPurgeMillis: Long? = null
    val governor: TickGovernor = TickGovernor(plugin, governorSettings)
    val writeRate: WriteRate = WriteRate(total = { storage.counters.seqIssued })
    val purgeGate: PurgeGate = PurgeGate { restoring.composite.isRunning }
    val listeners: ListenerState = ListenerState(storage.groundPositions)
    val capturing: CaptureServices = CaptureServices(this)
    val restoring: RestoreServices = RestoreServices(this)
    val blockReleases: BlockReleaseQueue = BlockReleaseQueue(this)
    internal val scrubs: Scrubs = Scrubs(plugin)
    val lookup: LookupAction by lazy { LookupAction(this) }
    val coreProtectImport: CoreProtectImportAction by lazy { CoreProtectImportAction(this) }
    internal val borrows: Borrows = Borrows()
    internal val commandApi: CommandApi by lazy { CommandApi(this) }

    /** Tables the rollback path would otherwise build on a region thread the first time it needs them. */
    fun warmRollback() = restoring.warmRollback()

    /** Checks a player who logged out inside what a rollback has since written. */
    suspend fun rescueJoined(player: Player) = restoring.structureRestorer.rescueJoined(player)
}
