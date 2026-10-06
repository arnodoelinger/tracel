package com.tracel.engine.capture.world

import com.tracel.engine.world.WorldLog
import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.holder.HolderId
import com.tracel.model.log.Seq
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.WorldChange
import com.tracel.model.world.entity.EntityShape
import java.util.*

/** Turns captured edits into [WorldChange]s and appends them. */
public class WorldCaptureCoordinator(
    private val log: WorldLog,
    private val nextSeq: suspend () -> Seq,
    private val nextSeqRange: suspend (Int) -> Seq,
) {
    /**
     * Records every edit of one event that actually changed something.
     *
     * @return how many it wrote.
     */
    public suspend fun record(edits: BlockEdits): Int {
        val n = log.appendAll(edits, nextSeqRange)
        return n
    }

    /**
     * Appends one entity change.
     *
     * [at] is the block the entity occupied, passed rather than derived: an [EntityShape] holds a
     * position within a world and not which world, and guessing one from the other is how an
     * entity ends up filed under the overworld because that is where most things are.
     */
    public suspend fun recordEntity(
        action: ActionKind,
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        at: BlockPos,
        entity: UUID,
        before: EntityShape?,
        after: EntityShape?,
    ) {
        val shape = after ?: before ?: error("an entity change with neither a before nor an after says nothing")
        log.append(
            WorldChange(
                nextSeq(),
                action,
                cause,
                causedBy,
                epochMillis,
                at,
                ChangeSubject.Entity(entity, shape.type, before, after),
            )
        )
    }
}
