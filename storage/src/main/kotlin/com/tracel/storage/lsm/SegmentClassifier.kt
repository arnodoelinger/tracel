package com.tracel.storage.lsm

/**
 * Says which family of segments a row belongs to. The engine knows nothing about keys;
 * whoever opens it does.
 */
fun interface SegmentClassifier {
    /**
     * @param keyTag the first byte of the user key
     * @param valueFirst the first byte of the value, or `-1` without one
     */
    fun categoryOf(keyTag: Int, valueFirst: Int): Int

    companion object {
        const val STATE = 0

        val NONE = SegmentClassifier { _, _ -> STATE }
    }
}
