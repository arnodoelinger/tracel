package com.tracel.engine.rollback.structure

import com.tracel.annotations.CauseKind
import com.tracel.annotations.RunsOn
import com.tracel.annotations.ThreadContext
import com.tracel.model.world.BlockShape
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.EntityShape
import com.tracel.model.world.WorldChange

/** Works out what the world has to be put back to. */
@RunsOn(ThreadContext.ASYNC)
public class StructurePlanner {
    /**
     * Splits [changes] into what has to be created and what has to be taken away.
     *
     * [changes] is expected newest-first, the order every query returns.
     */
    public fun plan(changes: List<WorldChange>): Pair<List<StructureStep>, List<StructureStep>> {
        val create = mutableListOf<StructureStep>()
        val destroy = mutableListOf<StructureStep>()
        val newest = LinkedHashMap<Any, WorldChange>(changes.size.coerceAtMost(65_536))
        val oldest = HashMap<Any, WorldChange>(changes.size.coerceAtMost(65_536))
        for (change in changes) {
            val key = change.key()
            newest.putIfAbsent(key, change)
            oldest[key] = change
        }

        for ((key, last) in oldest) {
            val first = newest.getValue(key)
            when (val subject = last.subject) {
                is ChangeSubject.Block -> {
                    val expected = (first.subject as ChangeSubject.Block).after
                    // Place then explode in the same window used to net to air (before first =
                    // air, after last = air) and skip the chest. A blast rollback has to put
                    // back what the blast removed, even if you placed it two minutes ago.
                    val target = explosionBefore(first) ?: subject.before
                    if (target == expected) continue
                    val step = StructureStep.SetBlock(last.at, target, expected)
                    // Restoring to air is a removal, and a removal has to wait until the ledger
                    // has finished emptying whatever stood there.
                    if (target == BlockShape.AIR) destroy += step else create += step
                }

                is ChangeSubject.Entity -> {
                    val newest = first.subject as ChangeSubject.Entity
                    val now = newest.after
                    val exploded = first.cause == CauseKind.EXPLOSION && now == null && newest.before != null
                    val before = if (exploded) newest.before else subject.before
                    when {
                        before != null && before != now ->
                            create += StructureStep.SpawnEntity(last.at, subject.entity, before)
                        before == null && now != null -> {
                            val remove = StructureStep.RemoveEntity(last.at, subject.entity, now)
                            // Falling sand still occupying the coordinate when the block is
                            // put back drops as an item. Take the entity away in the create
                            // phase so undo spawns it *after* the block is gone again.
                            if (now.isFallingBlock()) create += remove else destroy += remove
                        }
                    }
                }
            }
        }

        return create to destroy
    }

    private fun WorldChange.key(): Any = when (val subject = subject) {
        is ChangeSubject.Block -> at
        is ChangeSubject.Entity -> subject.entity
    }

    private fun explosionBefore(newest: WorldChange): BlockShape? {
        if (newest.cause != CauseKind.EXPLOSION) return null
        val block = newest.subject as? ChangeSubject.Block ?: return null
        if (block.after != BlockShape.AIR || block.before == BlockShape.AIR) return null
        return block.before
    }
}

private fun EntityShape.isFallingBlock(): Boolean {
    val type = type.value
    return type == "minecraft:falling_block" || type.endsWith(":falling_block")
}
