package com.tracel.storage.chrono.capsule

/**
 * The blobs a block capsule carries beside its states, by [ExtraSide];
 * a side nobody wrote is `null`.
 */
internal class Extras(
    val entry: Array<ByteArray?>?,
    val exit: Array<ByteArray?>?,
    val after: Array<ByteArray?>?,
    val breaks: Array<ByteArray?>?,
) {
    companion object {
        val NONE = Extras(null, null, null, null)
    }
}
