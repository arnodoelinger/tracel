package com.tracel.plugin.integration.update

import io.github.z4kn4fein.semver.toVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseFeedTest {
    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre}
    """.trimIndent()

    private fun feed(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `newest release beats the running one`() {
        val found = ReleaseFeed.newest(feed(release("v1.1.0"), release("v1.2.0")), "1.0.0".toVersion())
        assertEquals("1.2.0".toVersion(), found?.version)
        assertEquals("https://modrinth.com/plugin/tracel/version/1.2.0", found?.page)
        assertEquals(found?.page, found?.download)
    }

    @Test
    fun `a dev build is recognised`() {
        assertEquals(true, "1.0.0-dev".toVersion().isDevelopment())
        assertEquals(false, "1.0.0-preview.1".toVersion().isDevelopment())
        assertEquals(false, "1.0.0".toVersion().isDevelopment())
    }

    @Test
    fun `nothing newer means nothing to say`() {
        assertNull(ReleaseFeed.newest(feed(release("v1.0.0")), "1.0.0".toVersion()))
    }

    @Test
    fun `an alpha is not offered to a stable server`() {
        assertNull(ReleaseFeed.newest(feed(release("v1.1.0-alpha", pre = true)), "1.0.0".toVersion()))
    }

    @Test
    fun `an alpha server is offered the next alpha`() {
        val found = ReleaseFeed.newest(feed(release("v1.0.0-beta", pre = true)), "1.0.0-alpha".toVersion())
        assertEquals("1.0.0-beta".toVersion(), found?.version)
    }

    @Test
    fun `drafts and junk are skipped`() {
        val body = feed(release("v2.0.0", draft = true), release("nightly"))
        assertNull(ReleaseFeed.newest(body, "1.0.0".toVersion()))
        assertNull(ReleaseFeed.newest("not json", "1.0.0".toVersion()))
    }
}
