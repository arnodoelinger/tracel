package com.tracel.engine.journal

/**
 * Thrown by [CrashPoint] to simulate the process dying mid-step.
 */
public class SimulatedCrash(stepIndex: Int) : Exception("simulated crash before step $stepIndex")

/**
 * A testing hook: makes [JournalExecutor] throw [SimulatedCrash] right before
 * a chosen step. A test uses this to crash an execution at every possible
 * step index in turn, then hands the same journal to a fresh executor and
 * asserts it resumes correctly.
 */
public class CrashPoint private constructor(private val crashBeforeStep: Int?) {
    public fun checkBefore(stepIndex: Int) {
        if (stepIndex == crashBeforeStep) throw SimulatedCrash(stepIndex)
    }

    public companion object {
        public val None: CrashPoint = CrashPoint(null)
        public fun before(stepIndex: Int): CrashPoint = CrashPoint(stepIndex)
    }
}
