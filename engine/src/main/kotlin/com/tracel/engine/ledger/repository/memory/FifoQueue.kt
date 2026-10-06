package com.tracel.engine.ledger.repository.memory

import com.tracel.model.lot.AccountLot
import com.tracel.model.lot.LotId
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/**
 * One account's placements, oldest first, as the single writer edits them.
 *
 * A doubly linked chain with an index by lot, so that the head can be taken and any lot can be taken out in constant
 * time. It is never shown to readers: the writer builds it from the account's [PersistentList], edits it, and
 * publishes the result back as a list, so no reader ever walks a chain that is being unlinked.
 */
internal class FifoQueue {
    private class Node(var entry: AccountLot) {
        var prev: Node? = null
        var next: Node? = null
    }

    private val byLot = HashMap<LotId, Node>()
    private var head: Node? = null
    private var tail: Node? = null

    /** Whether nothing is queued. */
    val isEmpty: Boolean get() = head == null

    /** The oldest entry, left in place. */
    fun peekFirst(): AccountLot? = head?.entry

    /** Takes the oldest entry out and returns it. */
    fun pollFirst(): AccountLot? {
        val node = head ?: return null
        unlink(node)
        return node.entry
    }

    /**
     * Queues [entry] at the place its sequence number gives, or, if its lot is already queued, swaps the entry where it
     * stands.
     */
    fun put(entry: AccountLot) {
        val existing = byLot[entry.lot.id]
        if (existing != null) {
            existing.entry = entry
            return
        }
        val node = Node(entry)
        byLot[entry.lot.id] = node
        insertBySeq(node)
    }

    /** Puts [replacement] in the slot [retired] holds, keeping its position; the replacement may be another lot. */
    fun replaceLot(retired: LotId, replacement: AccountLot) {
        val node = byLot.remove(retired) ?: return
        node.entry = replacement
        byLot[replacement.lot.id] = node
    }

    /** Takes [lotId] out wherever it stands and returns its entry, or `null` if it is not queued. */
    fun remove(lotId: LotId): AccountLot? {
        val node = byLot[lotId] ?: return null
        unlink(node)
        return node.entry
    }

    /**
     * Moves every entry of [other] into this queue in sequence order, leaving [other] empty.
     *
     * Each entry is rewritten by [rewrite] on the way, and the rewritten entry is handed to [onMoved].
     */
    fun merge(
        other: FifoQueue,
        rewrite: (AccountLot) -> AccountLot,
        onMoved: (AccountLot) -> Unit,
    ) {
        var cur = other.head
        while (cur != null) {
            val next = cur.next
            other.unlink(cur)
            val rewritten = rewrite(cur.entry)
            cur.entry = rewritten
            byLot[rewritten.lot.id] = cur
            insertBySeq(cur)
            onMoved(rewritten)
            cur = next
        }
    }

    /** This queue as the immutable list that readers are shown. */
    fun toPersistentList(): PersistentList<AccountLot> {
        val builder = persistentListOf<AccountLot>().builder()
        var cur = head
        while (cur != null) {
            builder.add(cur.entry)
            cur = cur.next
        }
        return builder.build()
    }

    /**
     * Links [node] at the place its sequence number gives: the tail and the head are checked first, because that is
     * where almost everything goes, and only then is the queue walked back from the tail.
     */
    private fun insertBySeq(node: Node) {
        val seq = node.entry.fifoSeq.raw
        val last = tail
        if (last == null) {
            head = node
            tail = node
            return
        }
        if (seq >= last.entry.fifoSeq.raw) {
            last.next = node
            node.prev = last
            tail = node
            return
        }
        val first = head ?: return
        if (seq <= first.entry.fifoSeq.raw) {
            node.next = first
            first.prev = node
            head = node
            return
        }
        var cur = last.prev
        while (cur != null && cur.entry.fifoSeq.raw > seq) cur = cur.prev
        val after = cur ?: return
        val next = after.next
        node.prev = after
        node.next = next
        after.next = node
        next?.prev = node
    }

    /** Takes [node] out of the chain and out of [byLot]. */
    private fun unlink(node: Node) {
        val prev = node.prev
        val next = node.next
        if (prev != null) prev.next = next else head = next
        if (next != null) next.prev = prev else tail = prev
        node.prev = null
        node.next = null
        byLot.remove(node.entry.lot.id)
    }

    companion object {
        fun from(list: PersistentList<AccountLot>?): FifoQueue {
            val queue = FifoQueue()
            if (list != null) for (entry in list) queue.put(entry)
            return queue
        }
    }
}
