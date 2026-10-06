package com.tracel.engine.foreign

import com.tracel.engine.world.edit.BlockEdits
import com.tracel.model.cause.CauseKind
import com.tracel.model.event.EventKind
import com.tracel.model.flow.Flow
import com.tracel.model.holder.HolderId
import com.tracel.model.world.ActionKind
import com.tracel.model.world.BlockPos
import com.tracel.model.world.ChangeSubject
import com.tracel.model.world.entity.EntityTypeKey
import java.util.UUID

/**
 * Foreign history is what another plugin recorded, filed under this one's.
 *
 * It is older than anything recorded here and has to read that way in every index.
 */
public sealed interface ForeignRecord {
    /** Block edits that happened together. */
    public data class Blocks(public val edits: BlockEdits) : ForeignRecord

    /** One change to the world that stands alone: an entity gone, a block clicked. */
    public data class Change(
        public val action: ActionKind,
        public val cause: CauseKind,
        public val causedBy: HolderId?,
        public val epochMillis: Long,
        public val at: BlockPos,
        public val subject: ChangeSubject,
    ) : ForeignRecord

    /** Items changing hands. There are no lots behind these: the ledger never saw them. */
    public data class Moved(
        public val cause: CauseKind,
        public val causedBy: HolderId?,
        public val epochMillis: Long,
        public val at: BlockPos?,
        public val flows: List<Flow>,
    ) : ForeignRecord

    /** Something said or typed, or a session starting or ending. */
    public data class Happened(
        public val kind: EventKind,
        public val by: HolderId?,
        public val epochMillis: Long,
        public val at: BlockPos?,
        public val text: String?,
    ) : ForeignRecord
}

/**
 * How far into a source an import got: the last row read of each of its tables, in the order the importer
 * keeps them, and what came out of them so far.
 */
public data class ImportMark(public val rows: List<Long>, public val records: Long)

/**
 * What an import has to fit into.
 *
 * @property ownSince when this store's own history starts; a foreign record from then on is not taken
 * @property mark where the last import of the same source stopped
 */
public data class ImportRoom(public val ownSince: Long?, public val mark: ImportMark?)

/** The store numbered its own history from one, so there is nowhere older history could go. */
public class NoRoomForImport : IllegalStateException("this database numbers its own history from the start")

/** Handles history from other plugins, filed under this one's. */
public interface ForeignHistory {
    /** Makes sure there is room below our own history, and says how much of [source] is already in. */
    public suspend fun room(source: Long): ImportRoom

    /**
     * Files [records] in the order given, and notes that [source] was read up to [rows]. Both land together.
     *
     * @return how many records were written
     */
    public suspend fun append(source: Long, rows: List<Long>, records: List<ForeignRecord>): Int

    /** The sequence number below which everything imported sits. */
    public suspend fun importedBelow(): Long

    /** Writes down what [entity] was, for an import that learned it. */
    public suspend fun noteKind(entity: UUID, kind: EntityTypeKey)
}
