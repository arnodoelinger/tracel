package com.tracel.engine.rollback.journal.crash

/**
 * A testing hook: makes journal exectutor throw [SimulatedCrash] right before
 * a chosen step. A test uses this to crash an execution at every possible
 * step index in turn, then hands the same journal to a fresh executor and
 * asserts it resumes correctly.
 */
public class CrashPoint private constructor(private val crashBeforeStep: Int?) {
    /** Throws [SimulatedCrash] if [stepIndex] is the step this point was set to crash before. */
    public fun checkBefore(stepIndex: Int) {
        if (stepIndex == crashBeforeStep) throw SimulatedCrash(stepIndex)
    }

    public companion object {
        public val None: CrashPoint = CrashPoint(null)

        public fun before(stepIndex: Int): CrashPoint = CrashPoint(stepIndex)
    }
}
