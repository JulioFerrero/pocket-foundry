package app.pocketfoundry.updates

import app.pocketfoundry.core.updates.Updates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    private fun release(tag: String, body: String? = null, apk: Boolean = true, checksum: Boolean = true,
                        draft: Boolean = false, prerelease: Boolean = false): JSONObject {
        val name = "pocket-foundry-${tag.removePrefix("v")}.apk"
        val assets = listOfNotNull("$name.sha256".takeIf { checksum }, name.takeIf { apk })
            .map { JSONObject().put("name", it).put("browser_download_url", "https://dl/$it") }
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease)
            .put("assets", JSONArray(assets)).apply { if (body != null) put("body", body) }
    }

    private fun notes(vararg changes: String) = "**Since v0:**\n" + changes.joinToString("\n") { "- $it" } + "\n"

    @Test fun `versions compare number by number`() {
        assertTrue(Updates.compareVersions("0.10", "0.9") > 0)
        assertTrue(Updates.compareVersions("0.9", "0.10") < 0)
        assertTrue(Updates.compareVersions("0.6.10", "0.6.9") > 0)
        assertTrue(Updates.compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, Updates.compareVersions("0.7.5", "0.7.5"))
    }

    @Test fun `missing parts count as zero`() {
        assertEquals(0, Updates.compareVersions("1.0", "1"))
        assertTrue(Updates.compareVersions("1.0.1", "1") > 0)
    }

    @Test fun `a part that isn't a number counts as zero`() {
        // newest strips the v from tags first; the installed versionName has none.
        assertTrue(Updates.compareVersions("v1.0", "0.9") < 0)
        assertEquals(0, Updates.compareVersions("0.7.5-beta", "0.7.0"))
    }

    @Test fun `changes are the Since list as plain text`() {
        val notes = """
            Play your own copy of Factorio on Android.
            - not a change

            **Install:** open `pocket-foundry-0.7.5.apk` on the phone.

            **Since v0.7.4:**
            - Fix the [driver picker](https://github.com/x/y/issues/41) crash (#41)
            - **Faster** start with `xz` (#42, #43)
              a wrapped line
            - Saves keep their date

            **Source:** built from [v0.7.5](https://github.com/x/y).
        """.trimIndent()
        assertEquals(listOf("Fix the driver picker crash", "Faster start with xz", "Saves keep their date"),
                     Updates.changes(notes))
    }

    @Test fun `changes read Windows line endings`() {
        assertEquals(listOf("A", "B"), Updates.changes("**Since v1:**\r\n- A\r\n- B\r\n\r\n**Source:** x"))
    }

    @Test fun `notes without a Since list have no changes`() {
        assertEquals(emptyList<String>(), Updates.changes("**First release.** What's in it: see the README."))
        assertEquals(emptyList<String>(), Updates.changes(""))
    }

    @Test fun `a blank line under the heading ends the list before it starts`() {
        assertEquals(emptyList<String>(), Updates.changes("**Since v1:**\n\n- A\n"))
    }

    @Test fun `the newest release after the installed one wins, whatever the list order`() {
        val list = JSONArray(listOf(release("v0.9.0"), release("v0.10.0"), release("v0.7.5"), release("v0.7.4")))
        val update = Updates.newest(list, "0.7.5")!!
        assertEquals("0.10.0", update.version)
        assertEquals("https://dl/pocket-foundry-0.10.0.apk", update.apk)
        assertEquals("https://dl/pocket-foundry-0.10.0.apk.sha256", update.checksum)
    }

    @Test fun `the changes cover every newer release, newest first`() {
        val list = JSONArray(listOf(release("v0.7.5", notes("old")), release("v0.8.0", notes("A", "B")),
                                    release("v0.9.0", notes("C")), release("v0.7.6")))
        assertEquals(listOf("0.9.0" to listOf("C"), "0.8.0" to listOf("A", "B"), "0.7.6" to emptyList()),
                     Updates.newest(list, "0.7.5")!!.changes)
    }

    @Test fun `nothing newer gives no update`() {
        assertNull(Updates.newest(JSONArray(listOf(release("v0.7.5"), release("v0.7.4"))), "0.7.5"))
        assertNull(Updates.newest(JSONArray(), "0.7.5"))
    }

    @Test fun `drafts and prereleases are skipped`() {
        val list = JSONArray(listOf(release("v0.9.0", draft = true), release("v0.8.0", prerelease = true),
                                    release("v0.7.6")))
        assertEquals("0.7.6", Updates.newest(list, "0.7.5")!!.version)
    }

    @Test fun `a tag without a v works too`() {
        assertEquals("0.8.0", Updates.newest(JSONArray(listOf(release("0.8.0"))), "0.7.5")!!.version)
    }

    @Test fun `a newest release without its APK or checksum gives no update, even with an older one complete`() {
        assertNull(Updates.newest(JSONArray(listOf(release("v0.8.0", apk = false), release("v0.7.6"))), "0.7.5"))
        assertNull(Updates.newest(JSONArray(listOf(release("v0.8.0", checksum = false), release("v0.7.6"))), "0.7.5"))
    }
}
