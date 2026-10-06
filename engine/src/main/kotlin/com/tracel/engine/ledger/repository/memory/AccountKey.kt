package com.tracel.engine.ledger.repository.memory

/**
 * An account, one holder's stock of one item key, as two interned numbers packed into one long: the holder's number in
 * the high 32 bits, the item key's in the low 32.
 */
@JvmInline
internal value class AccountKey(val raw: Long) {
    companion object {
        /** The key of the account that [holder] and [item], both interned numbers, name. */
        fun pack(holder: Int, item: Int): AccountKey =
            AccountKey((holder.toLong() shl 32) or (item.toLong() and 0xFFFF_FFFFL))
    }
}
