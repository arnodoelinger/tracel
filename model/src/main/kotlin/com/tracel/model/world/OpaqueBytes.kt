package com.tracel.model.world

/**
 * A blob the model carries but never reads: equal when the bytes are, and only to its own kind.
 */
public abstract class OpaqueBytes(public val bytes: ByteArray) {
    final override fun equals(other: Any?): Boolean =
        this === other || (other is OpaqueBytes && javaClass == other.javaClass && bytes.contentEquals(other.bytes))

    final override fun hashCode(): Int = bytes.contentHashCode()

    final override fun toString(): String = "${javaClass.simpleName}(${bytes.size} bytes)"
}
