package com.tracel.plugin.services

import com.tracel.engine.actor.ActorFacts
import com.tracel.engine.container.ContainerSlotLog
import com.tracel.engine.event.EventLog
import com.tracel.engine.foreign.ForeignHistory
import com.tracel.engine.ledger.ItemForms
import com.tracel.engine.ledger.LotLedger
import com.tracel.engine.ledger.PendingDeliveryRepository
import com.tracel.engine.ledger.repository.LotRepository
import com.tracel.engine.log.RolledBack
import com.tracel.engine.log.TransactionLog
import com.tracel.engine.rollback.job.record.RollbackJobRepository
import com.tracel.engine.store.Counters
import com.tracel.engine.store.StoreAdmin
import com.tracel.engine.wear.WearLog
import com.tracel.engine.world.GroundPositions
import com.tracel.engine.world.WorldLog
import com.tracel.platform.storage.UnitOfWork

/**
 * Everything the engine keeps on disk, as the plugin sees it.
 *
 * @param repo stores the ledger lots
 * @param ledger tracks item ownership and balances
 * @param log stores transaction history
 * @param counters generates transaction and sequence IDs
 * @param unit the unit of work every write runs in
 * @param store what an operator can do to the stored history
 * @param jobs stores rollback jobs
 * @param pendingDeliveries stores pending item deliveries
 * @param events stores the event log
 * @param rolledBack which records a rollback took back
 * @param foreign history that came from somewhere else
 * @param groundPositions where items lay on the ground
 * @param worldLog stores world changes
 * @param containerSlots stores which slot a container's contents last sat in
 * @param wear stores how worn each tool was over time
 * @param actors stores a mob's type and a player's game mode
 * @param itemForms stores item metadata needed to reconstruct items
 */
class StorageService(
    val repo: LotRepository,
    val ledger: LotLedger,
    val log: TransactionLog,
    val counters: Counters,
    val unit: UnitOfWork,
    val store: StoreAdmin,
    val jobs: RollbackJobRepository,
    val pendingDeliveries: PendingDeliveryRepository,
    val events: EventLog,
    val rolledBack: RolledBack,
    val foreign: ForeignHistory,
    val groundPositions: GroundPositions,
    val worldLog: WorldLog,
    val containerSlots: ContainerSlotLog,
    val wear: WearLog,
    val actors: ActorFacts,
    val itemForms: ItemForms,
)
