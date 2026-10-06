package com.tracel.engine.rollback.structure

import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.block.BlockShape
import java.util.*
import com.tracel.engine.rollback.structure.space.CellEnds

@RunsOn(ThreadContext.ASYNC)
public class StructurePlanner(private val rules: WorldRules) {
    /** The steps to restore the world to the state at the start of the window. */
    public class Outcome(
        public val create: List<StructureStep>,
        public val destroy: List<StructureStep>,
        public val emptyToEmpty: Set<BlockPos>,
        public val bornAndGone: Set<UUID>,
    )

    /** Plan the steps to restore the world to the state at the start of the window. */
    public fun plan(changes: List<WorldChange>): Pair<List<StructureStep>, List<StructureStep>> =
        planAll(changes).let { it.create to it.destroy }

    /**
     * The steps, [cellsEmptyToEmpty] and [entitiesBornAndGone] in one pass: all three group the changes by the same key
     * and look at the same oldest and newest end, so asking for them one at a time built that map three times over.
     */
    public fun planAll(changes: List<WorldChange>): Outcome {
        val create = mutableListOf<StructureStep>()
        val destroy = mutableListOf<StructureStep>()
        val emptyToEmpty = HashSet<BlockPos>()
        val bornAndGone = HashSet<UUID>()

        val cells = CellEnds(changes)
        val entities = HashMap<UUID, Array<WorldChange>>()
        for ((index, change) in changes.withIndex()) {
            when (val subject = change.subject) {
                is ChangeSubject.Block -> cells.note(index)
                is ChangeSubject.Entity -> {
                    val slot = entities.getOrPut(subject.entity) { arrayOf(change, change) }
                    if (change.seq.raw > slot[NEWEST].seq.raw) slot[NEWEST] = change
                    if (change.seq.raw < slot[OLDEST].seq.raw) slot[OLDEST] = change
                }
            }
        }

        val shapes = HashMap<BlockShape, BlockShape>()

        cells.forEach { oldest, newest ->
            val subject = oldest.subject as ChangeSubject.Block
            val expected = (newest.subject as ChangeSubject.Block).after
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
            //    Empty -> Chest -> Empty
            //
            // If our chest was placed and destroyed entirely (!) inside the selected window, restoring
            // the state from the start of the window means (!) restoring air. The rollback must not
            // recreate the chest just because an explosion destroyed it later in the same window.
            //
            // In contrast:
            //
            //   Chest -> Empty,
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
            if (rules.isEmpty(target) && rules.isEmpty(expected)) emptyToEmpty += oldest.at
            if (target == expected) return@forEach
            val step = StructureStep.SetBlock(
                oldest.at,
                shapes.getOrPut(target) { target },
                shapes.getOrPut(expected) { expected },
            )

            // Restoring to air is a removal, and a removal has to wait until the ledger
            // has finished emptying whatever stood there.
            if (rules.isEmpty(target)) destroy += step else create += step
        }

        for (slot in entities.values) {
            val oldest = slot[OLDEST]
            val newest = slot[NEWEST]
            val subject = oldest.subject as ChangeSubject.Entity
            val now = (newest.subject as ChangeSubject.Entity).after
            val before = subject.before
            if (before == null && now == null) bornAndGone += subject.entity
            when {
                now == null -> {
                    // Lived at window open: put it back. Came and went inside the window, however
                    // it went: leave it gone, same as a block placed and blown up. Respawning it
                    // handed the placer the item back and hung the frame too, and re-armed crystals.
                    if (before != null && !rules.isMovingBlock(before)) {
                        create += StructureStep.SpawnEntity(oldest.at, subject.entity, before)
                    }
                }

                before == null -> {
                    val remove = StructureStep.RemoveEntity(oldest.at, subject.entity, now)
                    // Falling sand still here when the block returns drops as an item.
                    // Remove it in create so undo spawns it after the block is gone again.
                    // Hangings: same trap, nail included.
                    if (rules.isMovingBlock(now) || rules.dropsWhenBlockReturns(now)) create += remove else destroy += remove
                }

                before != now ->
                    // Still standing: change-in-place; undo puts the newer shape back
                    create += StructureStep.SpawnEntity(oldest.at, subject.entity, before, now)
            }
        }

        // Hangings cannot share a cell with a block. Don't fucking break them.
        val hangingIn = HashSet<BlockPos>()
        for (step in create) if (step is StructureStep.SpawnEntity && rules.isHanging(step.shape)) hangingIn += step.at
        if (hangingIn.isNotEmpty()) {
            create.removeAll { it is StructureStep.SetBlock && it.at in hangingIn && !rules.isEmpty(it.target) }
        }

        return Outcome(create, destroy, emptyToEmpty, bornAndGone)
    }

    /**
     * Cells that were empty at both window ends — do not dump restored items into a container that never comes back.
     */
    public fun cellsEmptyToEmpty(changes: List<WorldChange>): Set<BlockPos> = planAll(changes).emptyToEmpty

    /** Entities born and gone inside the window: the rollback leaves them gone, so nothing may be handed to them. */
    public fun entitiesBornAndGone(changes: List<WorldChange>): Set<UUID> = planAll(changes).bornAndGone

    private companion object {
        const val NEWEST = 0
        const val OLDEST = 1
    }
}
