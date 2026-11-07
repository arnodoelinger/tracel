package com.tracel.storage

import com.tracel.platform.io.reason
import com.tracel.storage.ffm.Key
import com.tracel.storage.spi.EngineCursor
import com.tracel.storage.spi.EngineSnapshot
import com.tracel.storage.spi.KeyValueEngine
import com.tracel.storage.spi.MutationBatch
import java.lang.foreign.MemorySegment
import java.util.logging.Level
import java.util.logging.Logger

/**
 * One unit of storage work: a snapshot to read from and a batch to write into, committed
 * together or not at all.
 *
 * Not thread-safe: the thread that opened it writes it.
 */
class StorageUnit(
    private val snapshot: EngineSnapshot,
    val batch: MutationBatch,
) : AutoCloseable {
    private var onCommit: ArrayList<() -> Unit>? = null
    private var participants: ArrayList<UnitParticipant>? = null
    private var participantKeys: HashMap<Any, UnitParticipant>? = null
    private var savepoints: HashMap<Int, IntArray>? = null
    private var onAbort: ArrayList<() -> Unit>? = null
    private val hookLogger = Logger.getLogger(StorageUnit::class.java.name)

    fun get(key: ByteArray): MemorySegment? {
        val overlay = batch.lookup(Key(key))
        if (overlay !== MutationBatch.MISSING) return (overlay as ByteArray?)?.let(MemorySegment::ofArray)
        return snapshot.get(key)
    }

    fun exists(key: ByteArray): Boolean = get(key) != null

    fun put(key: ByteArray, value: ByteArray) {
        batch.put(key, value)
    }

    fun delete(key: ByteArray) {
        batch.delete(key)
    }

    fun putPinned(key: ByteArray, value: ByteArray) {
        batch.putPinned(key, value)
    }

    fun mark(): Int {
        val mark = batch.mark()
        val joined = participants ?: return mark
        (savepoints ?: HashMap<Int, IntArray>().also { savepoints = it })[mark] =
            IntArray(joined.size) { joined[it].savepoint() }
        return mark
    }

    fun rollbackTo(mark: Int) {
        batch.rollbackTo(mark)
        val joined = participants ?: return
        val saved = savepoints?.get(mark)
        for (i in joined.indices) joined[i].rollbackTo(saved?.getOrNull(i) ?: 0)
    }

    fun release(mark: Int) {
        batch.release(mark)
        savepoints?.remove(mark)
    }

    /**
     * The participant of this unit [key] names, made by [create] the first time it is asked for.
     *
     * A participant collects writes of its own and turns them into the batch just before it lands;
     * marks roll it back with the batch.
     */
    fun <P : UnitParticipant> participant(key: Any, create: () -> P): P {
        val byKey = participantKeys ?: HashMap<Any, UnitParticipant>().also { participantKeys = it }
        @Suppress("UNCHECKED_CAST")
        byKey[key]?.let { return it as P }
        val made = create()
        byKey[key] = made
        (participants ?: ArrayList<UnitParticipant>().also { participants = it }) += made
        return made
    }

    /**
     * Lets every participant write what it collected.
     *
     * Whoever commits the unit calls this, once, right before.
     */
    internal fun prepareCommit() {
        participants?.forEach { it.beforeCommit(this) }
    }

    /** Runs [action] once the batch has landed. A unit that throws never does. */
    fun afterCommit(action: () -> Unit) {
        (onCommit ?: ArrayList<() -> Unit>().also { onCommit = it }) += action
    }

    /**
     * What [afterCommit] queued, for whoever just wrote the batch.
     *
     * A hook that throws is logged and skipped.
     */
    internal fun committed() {
        onCommit?.forEach { hook ->
            runCatching(hook).onFailure {
                hookLogger.log(
                    Level.WARNING,
                    "Commit hook failed (${it.reason()}).",
                    it
                )
            }
        }
    }

    /** Runs [action] if the batch never lands: whatever was cached off it is a lie. */
    fun afterAbort(action: () -> Unit) {
        (onAbort ?: ArrayList<() -> Unit>().also { onAbort = it }) += action
    }

    /**
     * What [afterAbort] queued, for whoever just dropped the batch.
     *
     * A hook that throws is logged and skipped.
     */
    internal fun aborted() {
        onAbort?.forEach { hook ->
            runCatching(hook).onFailure {
                hookLogger.log(
                    Level.WARNING,
                    "Abort hook failed (${it.reason()}).",
                    it
                )
            }
        }
    }

    fun scan(prefix: ByteArray, from: ByteArray = prefix, to: ByteArray? = null): EngineCursor {
        if (batch.isEmpty()) return snapshot.scan(prefix, from, to)
        return OverlayCursor(batch, snapshot.scan(prefix, from, to), prefix, from, to)
    }

    override fun close() {
        snapshot.close()
    }

    companion object {
        fun open(engine: KeyValueEngine): StorageUnit = StorageUnit(engine.snapshot(), MutationBatch())
    }
}
