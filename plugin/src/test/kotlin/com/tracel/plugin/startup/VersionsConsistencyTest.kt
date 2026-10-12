package com.tracel.plugin.startup

import com.tracel.platform.Versions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class VersionsConsistencyTest {
    private val oldest = Versions.Minecraft.SUPPORTED
        .minWith(compareBy({ it.first }, { it.second }))
        .let { (major, minor) -> "$major.$minor" }

    private val newest = Versions.Minecraft.SUPPORTED
        .maxWith(compareBy({ it.first }, { it.second }))
        .let { (major, minor) -> "$major.$minor" }

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun valueIn(file: String, pattern: String): String {
        val text = Files.readString(root().resolve(file))
        return Regex(pattern).find(text)?.groupValues?.get(1) ?: error("$file has no match for $pattern")
    }

    private fun assertAgrees(file: String, found: String, expected: String, which: String, exact: Boolean) {
        val agrees = if (exact) found == expected else found == expected || found.startsWith("$expected.")
        assertTrue(agrees, "$file says Minecraft $found, but the $which in Versions.Minecraft.SUPPORTED is $expected")
    }

    private fun assertOldest(file: String, found: String, exact: Boolean) =
        assertAgrees(file, found, oldest, "oldest", exact)

    @Test
    fun `paper-plugin api-version is the oldest supported release`() {
        val found = valueIn("plugin/src/main/resources/paper-plugin.yml", """api-version:\s*'?([0-9.]+)'?""")
        assertOldest("paper-plugin.yml", found, exact = true)
    }

    @Test
    fun `the development server runs the newest supported release`() {
        val found = valueIn("plugin/build.gradle.kts", """minecraftVersion\("([^"]+)"\)""")
        assertAgrees("plugin/build.gradle.kts", found, newest, "newest", exact = false)
    }

    @Test
    fun `the Paper API is built against the oldest supported release`() {
        val found = valueIn("gradle/libs.versions.toml", """paper\s*=\s*"([^"]+)"""")
        assertOldest("gradle/libs.versions.toml", found, exact = false)
    }
}
