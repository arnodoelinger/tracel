package com.tracel.engine.rollback.structure

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.entity.EntityShape
import java.util.*

// TODO: rewrite, this must not exist here
@RunsOn(ThreadContext.ASYNC)
@Unstable
public class StructurePlanner {
    public fun plan(changes: List<WorldChange>): Pair<List<StructureStep>, List<StructureStep>> {
        val create = mutableListOf<StructureStep>()
        val destroy = mutableListOf<StructureStep>()

        val ends = HashMap<Any, Array<WorldChange>>(changes.size.coerceAtMost(65_536))
        for (change in changes) {
            val slot = ends.getOrPut(change.key()) { arrayOf(change, change) }
            if (change.seq.raw > slot[NEWEST].seq.raw) slot[NEWEST] = change
            if (change.seq.raw < slot[OLDEST].seq.raw) slot[OLDEST] = change
        }

        for (slot in ends.values) {
            val last = slot[OLDEST]
            val first = slot[NEWEST]
            when (val subject = last.subject) {
                is ChangeSubject.Block -> {
                    val expected = (first.subject as ChangeSubject.Block).after
                    // The result of a productive discussion with Apehum about how "t:"
                    // should behave around explosions.
                    //
                    // The question was whether "t:10d" should mean "undo everything that happened during
                    // the last ten days" or "restore the world to the state it had ten days ago". These
                    // sound equivalent for ordinary changes, but they are absolutely not equivalent around
                    // (!) explosions.
                    //
                    // For example:
                    //
                    //    Air -> Chest -> Air
                    //
                    // If our chest was placed and destroyed entirely (!) inside the selected window, restoring
                    // the state from the start of the window means (!) restoring air. The rollback must not
                    // recreate the chest just because an explosion destroyed it later in the same window.
                    //
                    // In contrast:
                    //
                    //   Chest -> Air,
                    //
                    // where the chest already existed when the window opened, means the state at the start
                    // of the window contained the chest, so the explosion must restore it.
                    //
                    // The important boundary is therefore the oldest matched change: "before" is the state
                    // that existed immediately before the first change included by the query, while the
                    // newest "after" is the state at the other end of the window. Tracel does not treat
                    // the explosion itself as the thing being individually "undone".
                    //
                    // This also means that a block which was placed and then exploded inside the same
                    // rollback window can correctly remain air. The explosion is part of the history being
                    // rolled back, not an instruction to recreate every block it destroyed.
                    //
                    // Changing this to "restore everything destroyed by an explosion" changes the meaning of
                    // time and can make rollbacks recreate objects that never existed at the beginning of the
                    // selected window.
                    val target = subject.before
                    if (target == expected) continue
                    val step = StructureStep.SetBlock(last.at, target, expected)

                    // Restoring to air is a removal, and a removal has to wait until the ledger
                    // has finished emptying whatever stood there.
                    if (target.isAirLike) destroy += step else create += step
                }

                is ChangeSubject.Entity -> {
                    val newest = first.subject as ChangeSubject.Entity
                    val now = newest.after
                    val before = subject.before
                    when {
                        now == null -> {
                            // Lived at window open: put it back. Came and went inside the window, however
                            // it went: leave it gone, same as a block placed and blown up. Respawning it
                            // handed the placer the item back and hung the frame too, and re-armed crystals.
                            if (before != null && !before.isFallingBlock()) {
                                create += StructureStep.SpawnEntity(last.at, subject.entity, before)
                            }
                        }
                        before == null -> {
                            val remove = StructureStep.RemoveEntity(last.at, subject.entity, now)
                            // Falling sand still here when the block returns drops as an item.
                            // Remove it in create so undo spawns it after the block is gone again.
                            //
                            // Hangings: same trap, nail included. Removals run before block writes
                            // *within* a phase — a painting left in `destroy` was still on the wall
                            // while create rebuilt the block, vanilla popped it (ENTITY_REMOVE WORLD,
                            // item on the floor), our remove reported [Absent] and never journaled,
                            // undo had nothing to put back. Don't leave hangings in destroy.
                            if (now.isFallingBlock() || now.popsWhenABlockReturns()) create += remove else destroy += remove
                        }
                        before != now ->
                            // Still standing: change-in-place; undo puts the newer shape back.
                            create += StructureStep.SpawnEntity(last.at, subject.entity, before, now)
                    }
                }
            }
        }

        // Hangings cannot share a cell with a block. Don't fucking break them.
        val hangingIn = HashSet<BlockPos>()
        for (step in create) if (step is StructureStep.SpawnEntity && step.shape.hangs()) hangingIn += step.at
        if (hangingIn.isNotEmpty()) {
            create.removeAll { it is StructureStep.SetBlock && it.at in hangingIn && !it.target.isAirLike }
        }

        return create to destroy
    }

    /** Cells that were air at both window ends — do not dump restored items into a chest that never comes back. */
    @Unstable
    public fun cellsAirToAir(changes: List<WorldChange>): Set<BlockPos> {
        val ends = HashMap<BlockPos, Array<WorldChange>>()
        for (change in changes) {
            if (change.subject !is ChangeSubject.Block) continue
            val slot = ends.getOrPut(change.at) { arrayOf(change, change) }
            if (change.seq.raw > slot[NEWEST].seq.raw) slot[NEWEST] = change
            if (change.seq.raw < slot[OLDEST].seq.raw) slot[OLDEST] = change
        }
        val out = HashSet<BlockPos>()
        for ((at, slot) in ends) {
            val oldest = (slot[OLDEST].subject as ChangeSubject.Block).before
            val newest = (slot[NEWEST].subject as ChangeSubject.Block).after
            if (oldest.isAirLike && newest.isAirLike) out += at
        }
        return out
    }

    /** Entities born and gone inside the window: the rollback leaves them gone, so nothing may be handed to them. */
    @Unstable
    public fun entitiesBornAndGone(changes: List<WorldChange>): Set<UUID> {
        val ends = HashMap<UUID, Array<WorldChange>>()
        for (change in changes) {
            val subject = change.subject as? ChangeSubject.Entity ?: continue
            val slot = ends.getOrPut(subject.entity) { arrayOf(change, change) }
            if (change.seq.raw > slot[NEWEST].seq.raw) slot[NEWEST] = change
            if (change.seq.raw < slot[OLDEST].seq.raw) slot[OLDEST] = change
        }
        val out = HashSet<UUID>()
        for ((uuid, slot) in ends) {
            val born = (slot[OLDEST].subject as ChangeSubject.Entity).before == null
            val gone = (slot[NEWEST].subject as ChangeSubject.Entity).after == null
            if (born && gone) out += uuid
        }
        return out
    }

    private fun WorldChange.key(): Any = when (val subject = subject) {
        is ChangeSubject.Block -> at
        is ChangeSubject.Entity -> subject.entity
    }

    private companion object {
        const val NEWEST = 0
        const val OLDEST = 1
    }
}

@Unstable
private fun EntityShape.isFallingBlock(): Boolean {
    val type = type.value
    return type == "minecraft:falling_block" || type.endsWith(":falling_block")
}

@Unstable
private fun EntityShape.hangs(): Boolean {
    val name = type.value.substringAfter(':')
    return name == "item_frame" || name == "glow_item_frame" || name == "painting" ||
        name == "leash_knot"
}

/** Painting pops if the supporting block is restored first. */
@Unstable
private fun EntityShape.popsWhenABlockReturns(): Boolean =
    type.value.substringAfter(':') == "painting"
