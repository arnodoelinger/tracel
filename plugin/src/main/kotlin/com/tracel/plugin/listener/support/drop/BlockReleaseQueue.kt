package com.tracel.plugin.listener.support.drop

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.model.flow.Flow
import com.tracel.model.flow.FlowKind
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import com.tracel.plugin.listener.support.flow.flowsFor
import com.tracel.plugin.listener.support.flow.worldgenMintFlows
import kotlinx.coroutines.*
import org.bukkit.World
import org.bukkit.block.Block
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds

private val logger = Logger.getLogger("BlockReleaseQueue")

/**
 * Block release.
 *
 * One emptying, located in the world — death drops use the same path and are not blocks.
 */
data class BlockRelease(
    val holder: HolderId,
    val world: World,
    val x: Int,
    val y: Int,
    val z: Int,
    val contents: Map<ItemKey, Long>? = null,
) {
    constructor(holder: HolderId, block: Block) : this(holder, block.world, block.x, block.y, block.z)
}

/**
 * Batches block empties so claim windows, ledger reads, and vanilla drops become one story.
 *
 * Region thread sees the break; storage has believed stock; vanilla drops a tick later. Open the
 * window first, then mint-then-move unseen material, move claimed drops, burn believed leftovers.
 *
 * Empty drops write nothing.
 */
@Unstable
class BlockReleaseQueue(private val services: TracelServices) {
    private class Batch(
        val releases: List<BlockRelease>,
        val tokens: LongArray,
        val cause: CauseKind,
        val causedBy: HolderId?,
        val epochMillis: Long,
        val at: BlockPos?,
        val closesAt: Long,
    ) {
        var believed: List<Map<ItemKey, Long>>? = null
    }

    private class FollowUp(val flow: Flow, val cause: CauseKind, val causedBy: HolderId?, val epochMillis: Long, val at: BlockPos?)

    private val open = ConcurrentLinkedQueue<Batch>()

    private val waitingOn = ConcurrentHashMap<HolderId, Long>()
    private val followUps = ConcurrentHashMap<Long, ConcurrentLinkedQueue<FollowUp>>()
    private val outstanding = ConcurrentHashMap<Long, Int>()

    /**
     * [drop] spawned inside the window [token] belongs to; whatever happens to it waits for that window,
     * and the window waits for its claim ([claimed]) past its time, up to a ceiling: a region at 6 TPS
     * reached the claim after the window had burned the stock and the drop was minted unattributed.
     */
    fun hold(drop: HolderId, token: Long) {
        waitingOn[drop] = token
        outstanding.merge(token, 1, Int::plus)
    }

    /** The claim [hold] promised for [token] is in. */
    fun claimed(token: Long) {
        outstanding.computeIfPresent(token) { _, left -> if (left <= 1) null else left - 1 }
    }

    /**
     * Queues [flow] behind the window [flow]'s source drop is waiting for.
     *
     * A hopper under a farm or a player standing on the block picks the drop up in a tick or two; booked
     * at once that pickup found no lot and was rejected, and the item went off the books for good.
     *
     * @return `false` when the drop waits for nothing and the flow can go straight to the ledger.
     */
    fun afterRelease(flow: Flow, cause: CauseKind, causedBy: HolderId?, epochMillis: Long, at: BlockPos? = null): Boolean {
        val token = waitingOn[flow.source] ?: return false
        followUps.computeIfAbsent(token) { ConcurrentLinkedQueue() } += FollowUp(flow, cause, causedBy, epochMillis, at)
        return true
    }

    /** Region-thread open: the claim window must exist before the next tick's vanilla spawn. */
    fun open(
        releases: List<BlockRelease>,
        cause: CauseKind,
        causedBy: HolderId?,
        epochMillis: Long,
        at: BlockPos? = null,
    ) {
        if (releases.isEmpty()) return
        val tokens = LongArray(releases.size) { i ->
            val release = releases[i]
            services.blockDrops.open(release.world, release.x, release.y, release.z)
        }
        open += Batch(releases, tokens, cause, causedBy, epochMillis, at, System.currentTimeMillis() + CLAIM_WINDOW_MILLIS)
    }

    /** Starts a coroutine, runs `pass()`. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            delay(TICK_MILLIS.milliseconds)
            runCatching { pass() }.onFailure { logger.log(Level.WARNING, "block release pass failed; the loop continues", it) }
        }
    }

    /**
     * Drain the batches already open for a lookup / rollback that must see the drops. Ones opened
     * after the call are not its business: on a live server the queue is never empty.
     */
    suspend fun flush(timeoutMs: Long = CLAIM_WINDOW_MILLIS * 2) {
        val now = System.currentTimeMillis()
        val deadline = now + timeoutMs
        val opened = now + CLAIM_WINDOW_MILLIS
        while (true) {
            pass()
            if (open.none { it.closesAt <= opened } || System.currentTimeMillis() >= deadline) return
            delay(FLUSH_POLL_MILLIS.milliseconds)
        }
    }

    /** Credit believed stock into still-open windows, then close those whose claim window has elapsed. */
    suspend fun pass() {
        val uncredited = open.filter { it.believed == null }
        if (uncredited.isNotEmpty()) {
            services.atomically {
                for (batch in uncredited) {
                    batch.believed = batch.releases.mapIndexed { i, release ->
                        val believed = release.contents ?: services.ledger.totalsAt(release.holder).mapValues { it.value.raw }
                        services.blockDrops.credit(batch.tokens[i], believed)
                        believed
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val closed = mutableListOf<Batch>()
        for (batch in open) {
            if (batch.closesAt > now) break
            val waiting = batch.tokens.any { outstanding.containsKey(it) }
            if (waiting && now < batch.closesAt + CLAIM_CEILING_MILLIS) continue
            closed += batch
        }
        if (closed.isEmpty()) return
        open.removeAll(closed.toSet())

        val finished = closed.map { batch -> batch to batch.releases.indices.map { i -> services.blockDrops.finish(batch.tokens[i]) } }

        // What waited on these windows goes right after them, in the same commit
        val tokens = closed.flatMapTo(HashSet()) { it.tokens.asIterable() }
        waitingOn.entries.removeIf { it.value in tokens }
        for (token in tokens) outstanding.remove(token)
        val after = tokens.flatMap { token -> followUps.remove(token).orEmpty() }

        services.atomically {
            val work = finished.map { (batch, results) ->
                val flows = batch.releases.flatMapIndexed { i, release ->
                    val contents = release.contents ?: return@flatMapIndexed flowsFor(release.holder, batch.believed?.getOrNull(i).orEmpty(), results[i])
                    val ledger = services.ledger.totalsAt(release.holder).mapValues { it.value.raw }
                    val unseen = contents.mapValues { (key, qty) -> qty - (ledger[key] ?: 0L) }.filterValues { it > 0L }
                    if (unseen.isNotEmpty()) services.capture.recordDirect(worldgenMintFlows(unseen, release.holder), batch.epochMillis - 1, CauseKind.WORLD, null, batch.at)
                    flowsFor(release.holder, contents, results[i])
                }
                batch to flows
            }
            for ((batch, flows) in work) {
                if (flows.isEmpty()) continue
                // Savepoint per batch: one unseen-material failure must not unwind the rest of the commit
                val mark = services.storage.read { mark() }
                try {
                    val (mints, rest) = flows.partition { it.kind == FlowKind.MINT && it.destination !is HolderId.Entity }
                    if (mints.isNotEmpty()) services.capture.recordDirect(mints, batch.epochMillis - 1, CauseKind.WORLD, null, batch.at)
                    if (rest.isNotEmpty()) services.capture.recordDirect(rest, batch.epochMillis, batch.cause, batch.causedBy, batch.at)
                    services.storage.read { release(mark) }
                } catch (e: IllegalStateException) {
                    services.storage.read { rollbackTo(mark) }
                    logger.log(Level.FINE, "block release touched untracked material, not recorded", e)
                }
            }
            for (follow in after) {
                val mark = services.storage.read { mark() }
                try {
                    services.capture.recordDirect(listOf(follow.flow), follow.epochMillis, follow.cause, follow.causedBy, follow.at)
                    services.storage.read { release(mark) }
                } catch (e: IllegalStateException) {
                    services.storage.read { rollbackTo(mark) }
                    logger.log(Level.FINE, "a move off a claimed drop found nothing to move, not recorded", e)
                }
            }
        }

        for ((batch, _) in finished) batch.releases.forEach { services.differ.forget(it.holder) }
    }

    private companion object {
        const val CLAIM_WINDOW_MILLIS = 500L
        const val CLAIM_CEILING_MILLIS = 5_000L
        const val TICK_MILLIS = 200L
        const val FLUSH_POLL_MILLIS = 20L
    }
}
