package com.tracel.plugin.startup

import com.tracel.plugin.TracelPlugin
import com.tracel.storage.TracelStorage
import com.tracel.storage.format.StoreFormat
import com.tracel.storage.ports.ops.InterruptedImport

/** Migrate the store to the latest format. */
internal fun migrateStore(plugin: TracelPlugin, storage: TracelStorage) {
    val outcome = StoreFormat.ensure(storage)
    if (outcome.migrated) plugin.logger.info("Migrated the database from format ${outcome.from} to ${outcome.to}.")
}

/** Resume an interrupted import. */
internal fun resumeImport(plugin: TracelPlugin, storage: TracelStorage) {
    val pending = InterruptedImport.pending(storage) ?: return
    plugin.logger.info("Finishing the import of ${pending.file}, which was cut short.")
    InterruptedImport.resume(storage)
    plugin.logger.info("Import finished.")
}
