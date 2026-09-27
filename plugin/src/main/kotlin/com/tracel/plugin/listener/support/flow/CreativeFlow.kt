package com.tracel.plugin.listener.support.flow

import com.tracel.model.holder.HolderId
import com.tracel.model.holder.SinkKind
import com.tracel.model.holder.SourceKind
import org.bukkit.GameMode
import org.bukkit.entity.Player

/** Creative source. */
val CREATIVE_SOURCE: HolderId.Source = HolderId.Source(SourceKind.CREATIVE)

/** Creative sink. */
val CREATIVE_SINK: HolderId.Sink = HolderId.Sink(SinkKind.CREATIVE)

/**
 * Spectator inventories are unused.
 *
 * Creative is a real holder: treating it otherwise emptied chests on rollback and
 * compensated nobody (menu mint / drag-off burn stay on the player).
 */
fun Player.isLedgeredHolder(): Boolean = gameMode != GameMode.SPECTATOR
