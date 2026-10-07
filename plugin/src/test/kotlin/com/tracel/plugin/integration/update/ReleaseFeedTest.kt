package com.tracel.plugin.integration.update

import io.github.z4kn4fein.semver.toVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReleaseFeedTest {
    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, jar: Boolean = true) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$pre,"html_url":"https://example.test/$tag",
         "assets":[${if (jar) """{"name":"Tracel-$tag.jar","browser_download_url":"https://example.test/$tag.jar"}""" else ""}]}
    """.trimIndent()

    private fun feed(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `newest release beats the running one`() {
        val found = ReleaseFeed.newest(feed(release("v1.1.0"), release("v1.2.0")), "1.0.0".toVersion())
        assertEquals("1.2.0".toVersion(), found?.version)
        assertEquals("https://example.test/v1.2.0.jar", found?.download)
        assertEquals("https://example.test/v1.2.0", found?.page)
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

    @Test
    fun `a release without a jar links to its page`() {
        val found = ReleaseFeed.newest(feed(release("v1.1.0", jar = false)), "1.0.0".toVersion())
        assertEquals(found?.page, found?.download)
    }
}
