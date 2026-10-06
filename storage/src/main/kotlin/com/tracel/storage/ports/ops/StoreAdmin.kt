package com.tracel.storage.ports.ops

import com.tracel.engine.store.Around
import com.tracel.engine.store.CaptureHealth
import com.tracel.engine.store.ExportSummary
import com.tracel.engine.store.PurgeReport
import com.tracel.engine.store.PurgeSpec
import com.tracel.engine.store.PurgeSummary
import com.tracel.engine.store.StoreAdmin as StoreAdminPort
import com.tracel.engine.store.StoreReport
import com.tracel.storage.TracelStorage
import com.tracel.storage.codec.History
import com.tracel.storage.format.StoreFormat
import java.nio.file.Path

/** [StoreAdminPort] over the one store. */
class StoreAdmin(private val storage: TracelStorage) : StoreAdminPort {
    override val capture: CaptureHealth = object : CaptureHealth {
        override val dropped: Long get() = storage.ring.dropped
        override val ringFull: Long get() = storage.ring.ringFull
        override val backlog: Long get() = storage.ring.backlog
        override suspend fun awaitApplied(timeoutMs: Long): Boolean = storage.ring.awaitApplied(timeoutMs)
    }

    override val liveBytes: Long get() = storage.engine.stats().liveBytes

    override val formatVersion: String get() = StoreFormat.CURRENT.toString()

    override fun report(): StoreReport {
        val engine = storage.engine
        fun rows(kind: Int) = engine.history(kind).sumOf { it.entries }
        val segments = listOf(History.BLOCKS, History.ITEMS, History.EVENTS, History.CONTAINERS)
            .flatMap(engine::history)
        return StoreReport(
            blockRows = rows(History.BLOCKS),
            itemRows = rows(History.ITEMS),
            eventRows = rows(History.EVENTS),
            containerRows = rows(History.CONTAINERS),
            oldestMillis = segments.filter { it.entries > 0 }.minOfOrNull { it.windowStartMillis },
            liveBytes = liveBytes,
        )
    }

    override suspend fun previewPurge(spec: PurgeSpec): PurgeReport = previewPurge(storage, spec)

    override suspend fun purgeSome(spec: PurgeSpec, around: Around): PurgeReport = purgeSome(storage, spec, around)

    override suspend fun purgeAll(): PurgeSummary = purgeAll(storage)

    override suspend fun exportTo(to: Path, stopped: () -> Boolean): ExportSummary = exportTo(storage, to, stopped)

    override suspend fun importFrom(from: Path, stopped: () -> Boolean, commit: () -> Boolean): ExportSummary =
        importFrom(storage, from, stopped, commit)

    override fun closeAfter(timeoutMillis: Long, last: suspend () -> Unit): Boolean =
        storage.closeAfter(timeoutMillis, last)
}
