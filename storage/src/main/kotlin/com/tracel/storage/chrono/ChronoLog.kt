package com.tracel.storage.chrono

import com.tracel.engine.log.lookup.LookupRegion
import com.tracel.engine.world.Chronicle
import com.tracel.engine.world.ChronicleStats
import com.tracel.engine.world.Rewound
import com.tracel.engine.world.Unlogged
import com.tracel.model.log.Seq
import com.tracel.model.world.WorldId
import com.tracel.storage.Interning
import com.tracel.storage.TracelStorage
import com.tracel.storage.chrono.diagenesis.Hint
import java.util.concurrent.ConcurrentHashMap
import com.tracel.storage.chrono.query.Rewound as RewoundTile

/**
 * The questions only history by place answers: what an area looked like at a moment,
 * frame by frame, and where the chain of states broke because something changed a block
 * without the log seeing.
 */
class ChronoLog(private val storage: TracelStorage, private val chrono: Chrono = Chrono.of(storage)) : Chronicle {
    private val interning: Interning get() = storage.interning
    private val worldIds = ConcurrentHashMap<WorldId, Int>()
    private val warmed = ConcurrentHashMap<Long, Unit>()

    override suspend fun rewind(region: LookupRegion, since: Long): List<Rewound> = storage.read {
        val (tiles, box) = LookupPlan.placeOf(this, interning, region) ?: return@read emptyList()
        rewound(Changes(interning, this), chrono.reader(this).rewind(tiles, since, box))
    }

    override suspend fun frames(region: LookupRegion, moments: List<Long>): List<List<Rewound>> = storage.read {
        val (tiles, box) = LookupPlan.placeOf(this, interning, region) ?: return@read moments.map { emptyList() }
        val changes = Changes(interning, this)
        chrono.reader(this).frames(tiles, moments.toLongArray(), box).map { rewound(changes, it) }
    }

    override suspend fun unlogged(region: LookupRegion, since: Long): List<Unlogged> = storage.read {
        val (tiles, box) = LookupPlan.placeOf(this, interning, region) ?: return@read emptyList()
        val changes = Changes(interning, this)
        chrono.reader(this).gaps(tiles, since, box).flatMap { g ->
            (0 until g.size).map { i ->
                Unlogged(
                    changes.pos(g.tile, g.cells[i]), Seq(g.seq[i]), g.millis[i],
                    changes.shape(g.expected[i], null), changes.shape(g.found[i], null),
                )
            }
        }.sortedByDescending { it.seq.raw }
    }

    override suspend fun quiet(world: WorldId, tileX: Int, tileZ: Int) {
        val id = worldIdOf(world) ?: return
        chrono.quiet(Tile.of(id, tileX, tileZ))
    }

    override suspend fun retire(world: WorldId) {
        val id = worldIdOf(world) ?: return
        chrono.hint(Hint.Retire(id))
    }

    override suspend fun warm(world: WorldId, tileX: Int, tileZ: Int) {
        val id = worldIdOf(world) ?: return
        val tile = Tile.of(id, tileX, tileZ)
        if (warmed.putIfAbsent(tile.packed, Unit) != null) return
        if (warmed.size > WARMED_CAP) warmed.clear()
        chrono.warm(tile)
    }

    override suspend fun stats(): ChronicleStats {
        val s = chrono.stats()
        return ChronicleStats(
            liveCapsules = s.waitingFragments,
            liveChanges = 0,
            liveBytes = 0,
            filedCapsules = s.merges,
            filedChanges = 0,
            filedBytes = s.mergedBytes,
            unlogged = 0,
        )
    }

    private fun rewound(changes: Changes, found: List<RewoundTile>): List<Rewound> = found.flatMap { rw ->
        (0 until rw.size).map { i ->
            Rewound(
                changes.pos(rw.tile, rw.cells[i]),
                changes.shape(rw.then[i], rw.thenExtras[i]),
                changes.shape(rw.now[i], rw.nowExtras[i])
            )
        }
    }

    private suspend fun worldIdOf(world: WorldId): Int? =
        worldIds[world] ?: storage.read { interning.findWorldId(this, world) }?.also { worldIds[world] = it }

    private companion object {
        const val WARMED_CAP = 16_384
    }
}
