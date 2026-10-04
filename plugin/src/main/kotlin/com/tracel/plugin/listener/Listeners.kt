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

/** Listener registration list. A kind of history that is turned off in the config loses its listeners. */
internal fun listenersOf(services: TracelServices): List<TracelListener> {
    val logging = services.logging
    return buildList {
        if (logging.blocks) addAll(blockListeners(services))
        if (logging.entities) addAll(entityListeners(services))
        if (logging.items) addAll(itemListeners(services))
        if (logging.events) add(ActorEventListener(services))
        addAll(sessionListeners(services))
    }
}

/** Cells, and the items that go with placing and breaking them. */
private fun blockListeners(services: TracelServices): List<TracelListener> = listOf(
    BlockChangeListener(services),
    NaturalChangeListener(services),
    BlockInteractListener(services),
    PistonListener(services),
    StructureCommandListener(services),
    ExplosionListener(services),
    RedstoneListener(services),
    BlockListener(services),
    ContainerListener(services),
    HarvestListener(services),
)

/** Entity lifecycles, and what mobs carry and drop. */
private fun entityListeners(services: TracelServices): List<TracelListener> = listOf(
    EntityLifecycleListener(services),
    EntityCargoListener(services),
    DeathListener(services),
)

/** Lots moving between holders, apart from the blocks and mobs above. */
private fun itemListeners(services: TracelServices): List<TracelListener> = listOf(
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
    LootCaptureListener(services),

    // Recipes
    CauldronListener(services),
    CompostListener(services),
)

/** The inspect wand, offline rollback deliveries, snapshot eviction. Always on. */
private fun sessionListeners(services: TracelServices): List<TracelListener> = listOf(
    InspectListener(services),
    PendingDeliveryListener(services),
    SnapshotEvictionListener(services),
    FreezeGuardListener(services),
    FluidFreezeListener(services),
    GapCommandListener(services),
)
