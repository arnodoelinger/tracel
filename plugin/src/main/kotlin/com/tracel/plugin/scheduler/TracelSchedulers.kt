package com.tracel.plugin.scheduler

import com.tracel.model.holder.HolderId
import com.tracel.platform.scheduler.TracelSchedulers
import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.plugin.Plugin
import java.util.*

/** [TracelSchedulers] backed by `Paper`'s `Folia`-safe schedulers. */
class TracelSchedulers(
    private val plugin: Plugin,
    override val storage: CoroutineDispatcher,
) : TracelSchedulers {
    override fun region(location: HolderId.Block): CoroutineDispatcher = RegionDispatcher(plugin, location)

    override fun entity(entity: UUID): CoroutineDispatcher = EntityDispatcher(plugin, entity)

    override val global: CoroutineDispatcher = GlobalDispatcher(plugin)

    override val async: CoroutineDispatcher = AsyncDispatcher(plugin)
}
