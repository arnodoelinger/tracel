package com.tracel.storage.lsm.wal

/**
 * What [WriteAheadLog.replay] found: the highest sequence it applied,
 * and the damage it stopped at, if any.
 */
class WalReplay(val highest: Long, val damage: WalDamage?)
