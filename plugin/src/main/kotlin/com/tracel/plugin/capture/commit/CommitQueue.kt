package com.tracel.plugin.capture.commit

import com.tracel.plugin.services.TracelServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("MaterialCapture")

private const val MAX_COMMIT_BATCH = 256

/**
 * Storage writes the capture path owes, committed in batches off the event thread.
 *
 * Each one runs under its own mark: a write that fails is rolled back alone and the rest of the batch stands.
 */
internal class CommitQueue(private val services: TracelServices) {
    private class Commit(val what: String, val ticket: Long, val work: suspend () -> Unit)

    private val commits = Channel<Commit>(Channel.UNLIMITED)
    private val committer = AtomicBoolean()

    /** Queues [work] for the next batch. [what] names it in the log if it fails. */
    fun committing(what: String, work: suspend () -> Unit) {
        val ticket = services.pendingCaptures.owed()
        if (committer.compareAndSet(false, true)) startCommitter()
        if (commits.trySend(Commit(what, ticket, work)).isFailure) services.pendingCaptures.done(ticket)
    }

    private fun startCommitter() {
        services.scope.launch {
            val batch = ArrayList<Commit>(MAX_COMMIT_BATCH)
            for (first in commits) {
                batch += first
                while (batch.size < MAX_COMMIT_BATCH) batch += commits.tryReceive().getOrNull() ?: break
                try {
                    commitAll(batch)
                } finally {
                    for (commit in batch) services.pendingCaptures.done(commit.ticket)
                    batch.clear()
                }
            }
        }.invokeOnCompletion {
            while (true) services.pendingCaptures.done(commits.tryReceive().getOrNull()?.ticket ?: break)
        }
    }

    private suspend fun commitAll(batch: List<Commit>) {
        try {
            services.atomically {
                for (commit in batch) {
                    val mark = services.mark()
                    try {
                        commit.work()
                        services.release(mark)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        services.rollbackTo(mark)
                        val level = if (e is IllegalStateException) Level.FINE else Level.WARNING
                        logger.log(level, "untracked material in ${commit.what}, not recorded", e)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logger.log(Level.WARNING, "a batch of ${batch.size} captures failed to commit", e)
        }
    }
}
