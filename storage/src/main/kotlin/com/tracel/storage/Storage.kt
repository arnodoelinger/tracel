package com.tracel.storage

import com.tracel.engine.ownership.SingleWriterGuard
import com.tracel.platform.storage.UnitOfWork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The one door into the `SQLite` file: every `Sqlite*` port goes through [read] or [write], and
 * neither of them cares which thread its caller happened to be on.
 *
 * Only one transaction is ever open at a time, guarded by [lock]. `SQLite` has exactly one writer
 * and this pool has exactly one connection, so two transactions racing was never going to end in
 * anything but a busy-timeout stall; suspending on a [Mutex] instead costs the storage thread
 * nothing and keeps it free to serve whoever holds the lock.
 */
class Storage(val exposed: Database, val dispatcher: CoroutineDispatcher) : UnitOfWork {
    private val lock = Mutex()
    private val writer = SingleWriterGuard()

    /** Reads inside the current unit of work, or opens one if there is none. */
    suspend fun <T> read(block: suspend JdbcTransaction.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) open.joined().block() else unit(block)
    }

    /** Like [read], but also asserts that every write really does land on one and the same thread. */
    suspend fun <T> write(block: suspend JdbcTransaction.() -> T): T {
        val open = currentCoroutineContext()[OpenUnit]
        return if (open != null) {
            writer.checkIn()
            open.joined().block()
        } else {
            unit {
                writer.checkIn()
                block()
            }
        }
    }

    override suspend fun <T> atomically(block: suspend () -> T): T =
        if (currentCoroutineContext()[OpenUnit] != null) block() else unit { block() }

    private suspend fun <T> unit(block: suspend JdbcTransaction.() -> T): T =
        lock.withLock {
            withContext(dispatcher) {
                suspendTransaction(exposed) {
                    val transaction = this
                    withContext(OpenUnit(transaction, Thread.currentThread())) { transaction.block() }
                }
            }
        }

    /** Carries the open transaction to everything nested inside it. */
    private class OpenUnit(
        private val transaction: JdbcTransaction,
        private val thread: Thread,
    ) : AbstractCoroutineContextElement(OpenUnit) {
        /**
         * The open transaction, once it is established that we are still on the thread that opened
         * it. A unit of work that hops to a region or entity thread mid-flight and then reaches
         * back into storage would be handing one `JDBC` connection to two threads at once, so it
         * fails here rather than corrupting something quietly three layers down.
         */
        fun joined(): JdbcTransaction {
            check(thread === Thread.currentThread()) {
                "unit of work opened on ${thread.name} was re-entered from ${Thread.currentThread().name} — " +
                    "an atomically { } block must not leave the storage thread"
            }
            return transaction
        }

        companion object Key : CoroutineContext.Key<OpenUnit>
    }
}
