package com.tracel.plugin.status.disk

/** There is no room left to write history, so the server may neither start nor go on. */
class CriticalDiskSpace(message: String) : IllegalStateException(message)
