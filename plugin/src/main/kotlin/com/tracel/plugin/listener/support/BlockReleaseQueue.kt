package com.tracel.plugin.listener.support

import com.tracel.annotations.CauseKind
import com.tracel.annotations.Unstable
import com.tracel.model.holder.HolderId
import com.tracel.model.item.ItemKey
import com.tracel.model.world.BlockPos
import com.tracel.plugin.TracelServices
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bukkit.World
import org.bukkit.block.Block

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

    private val open = ConcurrentLinkedQueue<Batch>()

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

    /** Drain open batches for a lookup / rollback that must see the drops. */
    suspend fun flush(timeoutMs: Long = CLAIM_WINDOW_MILLIS * 2) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            pass()
            if (open.isEmpty() || System.currentTimeMillis() >= deadline) return
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
                        val believed = release.contents
                            ?: services.ledger.totalsAt(release.holder).mapValues { it.value.raw }
                        services.blockDrops.credit(batch.tokens[i], believed)
                        believed
                    }
                }
            }
        }

        val now = System.currentTimeMillis()
        val closed = mutableListOf<Batch>()
        while (true) {
            val head = open.peek() ?: break
            if (head.closesAt > now) break
            closed += open.poll() ?: break
        }
        if (closed.isEmpty()) return

        val work = closed.map { batch ->
            val believed = batch.believed
            val flows = batch.releases.flatMapIndexed { i, release ->
                flowsFor(
                    release.holder,
                    believed?.getOrNull(i).orEmpty(),
                    services.blockDrops.finish(batch.tokens[i]),
                )
            }
            batch to flows
        }

        services.atomically {
            for ((batch, flows) in work) {
                if (flows.isEmpty()) continue
                // Savepoint per batch: one unseen-material failure must not unwind the rest of the commit
                val mark = services.storage.read { mark() }
                try {
                    services.capture.recordDirect(flows, batch.epochMillis, batch.cause, batch.causedBy, batch.at)
                    services.storage.read { release(mark) }
                } catch (e: IllegalStateException) {
                    services.storage.read { rollbackTo(mark) }
                    logger.log(Level.FINE, "block release touched untracked material, not recorded", e)
                }
            }
        }

        for ((batch, _) in work) batch.releases.forEach { services.differ.forget(it.holder) }
    }

    private companion object {
        const val CLAIM_WINDOW_MILLIS = 500L
        const val TICK_MILLIS = 200L
        const val FLUSH_POLL_MILLIS = 20L
    }
}
