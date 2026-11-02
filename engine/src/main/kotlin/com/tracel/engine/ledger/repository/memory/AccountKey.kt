package com.tracel.engine.ledger.repository.memory

import com.tracel.model.world.packPair

/**
 * An account, one holder's stock of one item key, as two interned numbers packed into one long: the holder's number in
 * the high 32 bits, the item key's in the low 32.
 */
@JvmInline
internal value class AccountKey(val raw: Long) {
    companion object {
        fun pack(holder: Int, item: Int): AccountKey =
            AccountKey(packPair(holder, item))
    }
}
