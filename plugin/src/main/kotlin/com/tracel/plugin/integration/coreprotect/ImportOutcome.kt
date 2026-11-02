package com.tracel.plugin.integration.coreprotect

import com.tracel.plugin.integration.coreprotect.tally.ImportTally

/** How an import ended. */
class ImportOutcome(val outlook: ImportOutlook, val tally: ImportTally, val stopped: Boolean, val tookMillis: Long)
