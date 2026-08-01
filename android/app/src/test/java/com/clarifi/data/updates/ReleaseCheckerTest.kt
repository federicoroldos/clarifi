package com.clarifi.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The release body follows a fixed convention (CLAUDE.md rule 17), so the parts
 * that read it can be pinned down without touching the network.
 */
class ReleaseCheckerTest {

    private val body = """
        ## What's new
        - Receipt scanning now runs on an updated model.
        * Fixed the dashboard jumping when switching accounts.

        ## Install
        - Windows: download `ClariFi-Setup-0.3.0.exe` below and run it.
        - Linux (Debian/Ubuntu): install it with `sudo apt install ./clarifi_0.3.0_amd64.deb`.
    """.trimIndent()

    @Test
    fun `only the what's new bullets are taken, never the install steps`() {
        val bullets = ReleaseChecker.whatsNew(body)

        assertEquals(
            listOf(
                "Receipt scanning now runs on an updated model.",
                "Fixed the dashboard jumping when switching accounts.",
            ),
            bullets,
        )
    }

    @Test
    fun `a body without the heading yields nothing rather than every line`() {
        assertTrue(ReleaseChecker.whatsNew("- a loose bullet\n- another").isEmpty())
        assertTrue(ReleaseChecker.whatsNew(null).isEmpty())
        assertTrue(ReleaseChecker.whatsNew("").isEmpty())
    }

    @Test
    fun `tags are compared as numbers, not as text`() {
        assertEquals(listOf(0, 3, 0), ReleaseChecker.semver("v0.3.0"))
        // Padded, so a two-part tag still compares against a three-part version.
        assertEquals(listOf(1, 2, 0), ReleaseChecker.semver("1.2"))
        // "0.10.0" is newer than "0.9.0"; string ordering would say otherwise.
        assertTrue(ReleaseChecker.semver("v0.10.0")[1] > ReleaseChecker.semver("v0.9.0")[1])
        assertEquals(listOf(0, 3, 0), ReleaseChecker.semver("v0.3.0-beta.1"))
        assertEquals(listOf(0, 0, 0), ReleaseChecker.semver("not-a-version"))
    }
}
