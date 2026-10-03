package com.tracel.plugin.listener

import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.material.inventory.CraftListener
import com.tracel.plugin.listener.material.inventory.HandMutationListener
import com.tracel.plugin.listener.material.inventory.InventoryClickListener
import com.tracel.plugin.listener.material.inventory.WearListener
import com.tracel.plugin.listener.material.item.*
import com.tracel.plugin.listener.material.machine.DispenseListener
import com.tracel.plugin.listener.material.machine.HopperListener
import com.tracel.plugin.listener.material.machine.SmeltListener
import com.tracel.plugin.listener.material.place.BlockListener
import com.tracel.plugin.listener.material.place.ContainerListener
import com.tracel.plugin.listener.material.place.HarvestListener
import com.tracel.plugin.listener.material.recipe.CauldronListener
import com.tracel.plugin.listener.material.recipe.CompostListener
import com.tracel.plugin.listener.session.*
import com.tracel.plugin.listener.world.cell.*
import com.tracel.plugin.listener.world.entity.EntityLifecycleListener
import com.tracel.plugin.listener.world.entity.ExplosionListener
import com.tracel.plugin.listener.world.entity.RedstoneListener

/** Listener registration list. */
internal fun listenersOf(services: TracelServices): List<TracelListener> =
    worldListeners(services) + materialListeners(services) + sessionListeners(services)

/** World log — what occupies a cell or entity UUID. Never items. */
private fun worldListeners(services: TracelServices): List<TracelListener> = listOf(
    // Cells
    BlockChangeListener(services),
    NaturalChangeListener(services),
    BlockInteractListener(services),
    PistonListener(services),
    StructureCommandListener(services),

    // Entities
    EntityLifecycleListener(services),
    ExplosionListener(services),
    RedstoneListener(services),
)

/** Transaction log — lots moving between holders. */
private fun materialListeners(services: TracelServices): List<TracelListener> = listOf(
    // Place, break, harvest
    BlockListener(services),
    ContainerListener(services),
    HarvestListener(services),

    // Inventories
    InventoryClickListener(services),
    HandMutationListener(services),
    CraftListener(services),
    WearListener(services),

    // Machines
    HopperListener(services),
    DispenseListener(services),
    SmeltListener(services),

    // Items
    ItemEntityListener(services),
    ProjectileListener(services),
    EntityCargoListener(services),
    DeathListener(services),
    LootCaptureListener(services),

    // Recipes
    CauldronListener(services),
    CompostListener(services),
)

/** The event log (chat, commands, sessions), inspect wand, offline rollback deliveries, snapshot eviction. */
private fun sessionListeners(services: TracelServices): List<TracelListener> = listOf(
    InspectListener(services),
    PendingDeliveryListener(services),
    SnapshotEvictionListener(services),
    FreezeGuardListener(services),
    GapCommandListener(services),
    ActorEventListener(services),
)
