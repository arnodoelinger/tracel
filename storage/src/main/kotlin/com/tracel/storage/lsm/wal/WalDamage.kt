package com.tracel.storage.lsm.wal

/**
 * Where a log stopped being readable.
 *
 * A [tear] is what a power cut usually leaves: past [at] there is no intact frame, only a cut-short one, zeros
 * or junk. When an intact frame does follow, either pages landed out of order or bytes went bad on the disk.
 */
class WalDamage(val at: Long, val size: Long, val tear: Boolean)
