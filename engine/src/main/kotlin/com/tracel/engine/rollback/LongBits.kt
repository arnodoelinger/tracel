package com.tracel.engine.rollback

/** Whether bit [index] is set in [words]. */
internal fun isSet(words: LongArray, index: Int): Boolean = words[index ushr 6] and (1L shl (index and 63)) != 0L

/** Sets bit [index] in [words]. */
internal fun setBit(words: LongArray, index: Int) {
    val word = index ushr 6
    words[word] = words[word] or (1L shl (index and 63))
}
