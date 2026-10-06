package com.tracel.plugin.importer.coreprotect

import com.tracel.plugin.importer.coreprotect.tally.ImportTally

/** How an import ended. */
class ImportOutcome(val outlook: ImportOutlook, val tally: ImportTally, val stopped: Boolean, val tookMillis: Long)
