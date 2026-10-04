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

    private fun root(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun valueIn(file: String, pattern: String): String {
        val text = Files.readString(root().resolve(file))
        return Regex(pattern).find(text)?.groupValues?.get(1) ?: error("$file has no match for $pattern")
    }

    private fun assertOldest(file: String, found: String, exact: Boolean) {
        val agrees = if (exact) found == oldest else found == oldest || found.startsWith("$oldest.")
        assertTrue(agrees, "$file says Minecraft $found, but the oldest in Versions.Minecraft.SUPPORTED is $oldest")
    }

    @Test
    fun `paper-plugin api-version is the oldest supported release`() {
        val found = valueIn("plugin/src/main/resources/paper-plugin.yml", """api-version:\s*'?([0-9.]+)'?""")
        assertOldest("paper-plugin.yml", found, exact = true)
    }

    @Test
    fun `the development server runs the oldest supported release`() {
        val found = valueIn("plugin/build.gradle.kts", """minecraftVersion\("([^"]+)"\)""")
        assertOldest("plugin/build.gradle.kts", found, exact = false)
    }

    @Test
    fun `the Paper API is built against the oldest supported release`() {
        val found = valueIn("gradle/libs.versions.toml", """paper\s*=\s*"([^"]+)"""")
        assertOldest("gradle/libs.versions.toml", found, exact = false)
    }
}
