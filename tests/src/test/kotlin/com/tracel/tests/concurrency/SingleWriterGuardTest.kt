package com.tracel.tests.concurrency

import com.tracel.engine.concurrency.SingleWriterGuard
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SingleWriterGuardTest {
    @Test
    fun `the owning thread can check in as many times as it wants`() {
        val guard = SingleWriterGuard()
        repeat(3) { guard.checkIn() }
    }

    @Test
    fun `a second thread touching the guard fails loudly instead of racing`() {
        val guard = SingleWriterGuard()
        guard.checkIn()

        val intruder = Executors.newSingleThreadExecutor()
        try {
            val future = intruder.submit { guard.checkIn() }
            val failure = assertThrows(ExecutionException::class.java) { future.get(5, TimeUnit.SECONDS) }
            assertInstanceOf(IllegalStateException::class.java, failure.cause)
        } finally {
            intruder.shutdown()
        }
    }
}
