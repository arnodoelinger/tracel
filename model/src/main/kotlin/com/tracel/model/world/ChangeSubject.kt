package com.tracel.model.world

import com.tracel.model.world.block.BlockShape
import com.tracel.model.world.entity.EntityShape
import com.tracel.model.world.entity.EntityTypeKey
import java.util.*

/** What a [WorldChange] happened to. */
public sealed interface ChangeSubject {
    /**
     * A block edit. [before] is what a rollback restores; [after] is what it checks the world
     * against first, so a coordinate somebody has since rebuilt gets reported rather than
     * silently flattened.
     */
    public data class Block(public val before: BlockShape, public val after: BlockShape) : ChangeSubject

    /**
     * An entity appearing, going away, or changing in place. Either side is `null` when the
     * entity did not exist on that side of the change.
     *
     * [WorldChange.at] is the block the entity was in, so an entity change lands in the same
     * spatial index as a block edit and one region scan finds both.
     */
    public data class Entity(
        public val entity: UUID,
        public val type: EntityTypeKey,
        public val before: EntityShape?,
        public val after: EntityShape?,
    ) : ChangeSubject
}
