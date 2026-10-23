package com.tracel.plugin.command.scrub

import kotlinx.coroutines.Job
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The players who are scrubbing right now, one [Scrub] each,
 * and those still waiting for history to be read.
 */
internal class Scrubs(private val plugin: Plugin) {
    private val sessions = ConcurrentHashMap<UUID, Scrub>()
    private val reads = ConcurrentHashMap<UUID, Job>()

    /** The scrub [player] is in, if any. */
    fun of(player: Player): Scrub? = sessions[player.uniqueId]

    /** [job] is reading history for [player]; a read they had going before is called off. */
    fun reading(player: Player, job: Job) {
        reads.put(player.uniqueId, job)?.cancel()
    }

    /** [job] is done reading. */
    fun read(player: Player, job: Job) {
        reads.remove(player.uniqueId, job)
    }

    /** Calls off the read of [player], if there is one. */
    fun cancelRead(player: Player): Boolean = reads.remove(player.uniqueId)?.also { it.cancel() } != null

    /**
     * Puts [player] into a scrub of [timeline], ending the one they were in.
     *
     * Safe from any thread.
     */
    fun begin(player: Player, timeline: ScrubTimeline) {
        player.scheduler.run(plugin, {
            sessions.remove(player.uniqueId)?.end(restore = true)
            val scrub = Scrub(plugin, player, player.world, timeline, player.inventory.heldItemSlot)
            sessions[player.uniqueId] = scrub
            scrub.start()
        }, { timeline.close() })
    }

    /**
     * Ends the scrub of [player], showing them the real world again.
     *
     * Safe from any thread.
     */
    fun end(player: Player) {
        player.scheduler.run(plugin, { sessions.remove(player.uniqueId)?.end(restore = true) }, null)
    }

    /** Forgets the scrub of [player] who is gone or somewhere else: nothing to show back. */
    fun drop(player: UUID) {
        reads.remove(player)?.cancel()
        sessions.remove(player)?.end(restore = false)
    }

    /** Forgets every scrub, at shutdown. */
    fun dropAll() {
        (sessions.keys + reads.keys).toList().forEach(::drop)
    }
}
