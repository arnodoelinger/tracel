package com.tracel.plugin.startup

import com.tracel.plugin.startup.version.MinecraftVersion
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MinecraftVersionTest {
    @Test
    fun `26_1_2 parses to major 26, minor 1`() {
        assertEquals(MinecraftVersion(26, 1), MinecraftVersion.parse("26.1.2"))
        assertEquals(MinecraftVersion(26, 1), MinecraftVersion.parse("26.1.2-R0.1-SNAPSHOT"))
    }

    @Test
    fun `below 26_1 refuses`() {
        val logger = RecordingLogger()
        assertThrows(IllegalStateException::class.java) { MinecraftVersion.check("1.21.1", fakePlugin(logger)) }
        assertTrue(logger.records.any { it.level == Level.SEVERE })
    }

    @Test
    fun `26_1 starts normally`() {
        assertFalse(MinecraftVersion.check("26.1.2", fakePlugin(RecordingLogger())))
    }

    @Test
    fun `newer than the newest tested is forward-compat`() {
        val logger = RecordingLogger()
        val newest = MinecraftVersion.newest
        assertTrue(MinecraftVersion.check("${newest.major}.${newest.minor + 1}.0", fakePlugin(logger)))
        assertTrue(logger.records.any { it.level == Level.WARNING })
    }

    @Test
    fun `garbage refuses`() {
        assertNull(MinecraftVersion.parse("not-a-version"))
        assertThrows(IllegalStateException::class.java) {
            MinecraftVersion.check("not-a-version", fakePlugin(RecordingLogger()))
        }
    }

    private fun fakePlugin(logger: Logger): Plugin {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getLogger" -> logger
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                "toString" -> "fakePlugin"
                else -> null
            }
        }
        return Proxy.newProxyInstance(Plugin::class.java.classLoader, arrayOf(Plugin::class.java), handler) as Plugin
    }

    private class RecordingLogger : Logger("MinecraftVersionTest", null) {
        val records = mutableListOf<LogRecord>()

        init {
            setUseParentHandlers(false)
        }

        override fun log(record: LogRecord) {
            records += record
        }
    }
}
