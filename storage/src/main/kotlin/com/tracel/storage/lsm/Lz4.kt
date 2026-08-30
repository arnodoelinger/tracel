package com.tracel.storage.lsm

import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4FastDecompressor

/** Pure-Kotlin LZ4. */
internal object Lz4 {
    private val java = LZ4Factory.fastestJavaInstance()
    val decompressor: LZ4FastDecompressor = java.fastDecompressor()
    fun compressor() = java.fastCompressor()
}
