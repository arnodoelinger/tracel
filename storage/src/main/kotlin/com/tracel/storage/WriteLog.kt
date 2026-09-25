package com.tracel.storage

import com.tracel.annotations.Unstable
import com.tracel.storage.codec.Keys
import com.tracel.storage.spi.MutationBatch
import java.util.logging.Logger

/** Prints every committed storage write. */
@Unstable
internal object WriteLog {
    @Volatile
    var enabled: Boolean = System.getProperty("tracel.writeLog") == "true" // -Dtracel.writeLog=true

    private val log = Logger.getLogger("Tracel/WriteLog")

    fun dump(batch: MutationBatch) {
        if (!enabled || batch.isEmpty()) return
        val lines = ArrayList<String>(batch.size)
        batch.forEach { key, value ->
            val tag = if (key.isNotEmpty()) Keys.tagName(key[0]) else "?"
            val hex = key.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val bytes = value?.size ?: 0
            val verb = if (value == null) "del" else "put"
            lines += "$verb $tag $hex ${bytes}b"
        }
        log.info("Commit ${batch.size} ${lines.joinToString("; ")}")
    }
}
