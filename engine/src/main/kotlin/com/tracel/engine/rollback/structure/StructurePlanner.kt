package com.tracel.engine.rollback.structure

import com.tracel.annotations.CauseKind
import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.annotations.Unstable
import com.tracel.model.world.BlockPos
import com.tracel.model.world.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.EntityShape
import com.tracel.model.world.WorldChange

/**
 * Builds the structural changes needed to restore the world state covered by a rollback.
 *
 * Block changes are reduced to their state at the start and end of the rollback window.
 * Entity changes are handled similarly, with special cases for spawning, removal, movement,
 * falling blocks, and hanging entities.
 */
@RunsOn(ThreadContext.ASYNC)
public class StructurePlanner {
    /**
     * Splits [changes] into structural changes that must be created and destroyed.
     *
     * For each block or entity, only the oldest and newest matching changes matter. The resulting
     * step restores the state from the beginning of the rollback window while keeping the state
     * that exists immediately before the rollback as its expected state.
     *
     * Entity restoration needs additional handling because some entities occupy block cells,
     * falling blocks interact with restored terrain, and hanging entities cannot share a cell with
     * a block.
     */
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
                    if (target == BlockShape.AIR) destroy += step else create += step
                }

                is ChangeSubject.Entity -> {
                    val newest = first.subject as ChangeSubject.Entity
                    val now = newest.after
                    val before = subject.before
                    when {
                        now == null -> {
                            // Existed when the window opened: put it back. Hung then punched:
                            // it was not there at the start. Leave it gone. Hung then blown:
                            // spawn the pose it died in (rotation, facing), cargo stripped.
                            val hungThenBlown = before == null &&
                                first.cause == CauseKind.EXPLOSION &&
                                !subject.isPrimedTnt()
                            val lived = when {
                                before != null -> before
                                hungThenBlown -> newest.before ?: subject.after
                                else -> null
                            }
                            if (lived != null && !lived.isFallingBlock()) {
                                create += StructureStep.SpawnEntity(last.at, subject.entity, lived)
                            }
                        }
                        before == null -> {
                            val remove = StructureStep.RemoveEntity(last.at, subject.entity, now)
                            // Falling sand still occupying the coordinate when the block is
                            // put back drops as an item. Take the entity away in the create
                            // phase so undo spawns it (!) after the block is gone again.
                            if (now.isFallingBlock()) create += remove else destroy += remove
                        }
                        before != now ->
                            // Still standing, so "now" travels with it. This is a change in
                            // place, and its undo puts the newer shape back rather than taking
                            // the entity away.
                            create += StructureStep.SpawnEntity(last.at, subject.entity, before, now)
                    }
                }
            }
        }

        // Hanging entities are annoying in every case. They cannot coexist with blocks.
        // Don't fucking break them.
        val hangingIn = HashSet<BlockPos>()
        for (step in create) if (step is StructureStep.SpawnEntity && step.shape.hangs()) hangingIn += step.at
        if (hangingIn.isNotEmpty()) {
            create.removeAll { it is StructureStep.SetBlock && it.at in hangingIn && it.target != BlockShape.AIR }
        }

        return create to destroy
    }

    /**
     * Finds block positions that were air at both ends of the rollback window.
     *
     * These positions may have contained a temporary block during the window, such as a block
     * that was placed and later destroyed. Callers can use the result to avoid delivering
     * restored material into a container or block that the structural plan does not recreate.
     */
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
            if (oldest == BlockShape.AIR && newest == BlockShape.AIR) out += at
        }
        return out
    }

    /**
     * Returns the key used to group changes belonging to the same world object.
     *
     * Blocks are grouped by position, while entities are grouped by entity identity.
     */
    private fun WorldChange.key(): Any = when (val subject = subject) {
        is ChangeSubject.Block -> at
        is ChangeSubject.Entity -> subject.entity
    }

    private companion object {
        const val NEWEST = 0
        const val OLDEST = 1
    }
}

/**
 * @return whether this shape represents a falling block entity.
 *
 * Falling blocks are handled differently from normal entities because restoring the block at their
 * position can cause the entity to drop as an item.
 */
@Unstable
private fun EntityShape.isFallingBlock(): Boolean {
    val type = type.value
    return type == "minecraft:falling_block" || type.endsWith(":falling_block")
}

/**
 * @return whether this entity occupies its block position as a hanging decoration.
 *
 * Hanging entities cannot share their position with a block. Restoring such a block first would
 * cause the entity to break and potentially drop items. So the structural plan handles the
 * position specially.
 */
@Unstable
private fun EntityShape.hangs(): Boolean {
    val name = type.value.substringAfter(':')
    return name == "item_frame" || name == "glow_item_frame" || name == "painting" ||
        name == "leash_knot"
}

/**
 * @return whether this entity shape represents primed TNT.
 *
 * Primed TNT is excluded from the normal explosion entity-restoration rule because restoring the
 * TNT entity itself would recreate an explosive entity that the rollback did not intend to revive.
 */
@Unstable
private fun ChangeSubject.Entity.isPrimedTnt(): Boolean {
    val type = this.type.value
    return type == "minecraft:tnt" || type.endsWith(":tnt")
}
