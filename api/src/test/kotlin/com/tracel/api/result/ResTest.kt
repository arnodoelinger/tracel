package com.tracel.api.result

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResTest {
    private fun half(n: Int): Res<Int, String> = if (n % 2 == 0) Res.Ok(n / 2) else Res.Err("$n is odd")

    @Test
    fun `res returns the value when every bind works`() {
        assertEquals(Res.Ok(2), res { half(half(8).bind()).bind() })
    }

    @Test
    fun `res stops at the first error and runs nothing after it`() {
        var reached = false
        val got = res<Int, String> {
            half(3).bind()
            reached = true
            0
        }
        assertEquals(Res.Err("3 is odd"), got)
        assertEquals(false, reached)
    }

    @Test
    fun `an inner res does not swallow an outer fail`() {
        val outer = res<Int, String> outer@{
            val inner = res<Int, String> { this@outer.fail("outer") }
            inner.getOrElse { 0 }
        }
        assertEquals(Res.Err("outer"), outer)
    }

    @Test
    fun `a scope smuggled out of its block refuses to fail`() {
        var leaked: ResScope<String>? = null
        res<Unit, String> { leaked = this }
        assertThrows<IllegalStateException> { leaked!!.fail("late") }
    }
}
