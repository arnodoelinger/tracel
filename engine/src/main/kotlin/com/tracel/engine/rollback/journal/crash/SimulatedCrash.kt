package com.tracel.engine.rollback.journal.crash

import com.tracel.engine.rollback.lease.support.KeepsLease

/** Thrown by [CrashPoint] to simulate the process dying mid-step. */
public class SimulatedCrash(stepIndex: Int) : Exception("simulated crash before step $stepIndex"), KeepsLease
