package com.marcusrosado.stamp

import com.marcusrosado.stamp.data.Release
import com.marcusrosado.stamp.data.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun versionsCompareAsNumbers() {
        assertTrue(Versions.newer("1.1.0", "1.0.0"))
        assertTrue(Versions.newer("v1.10.0", "1.9.3"))
        assertTrue(Versions.newer("1.0.1", "1.0"))
        assertFalse(Versions.newer("1.0", "1.0.0"))
        assertFalse(Versions.newer("1.0.0", "1.0.0"))
        assertFalse(Versions.newer("0.47.1", "1.0.0"))
        assertFalse(Versions.newer("1.1.0-beta", "1.0.0"))
        assertFalse(Versions.newer("2.0.0", "dev"))
    }

    private fun release(tag: String, assets: String, body: String = "", draft: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":false,"body":${org.json.JSONObject.quote(body)},"assets":[$assets]}"""

    private val apk = """{"name":"stamp.apk","size":4200,"browser_download_url":"https://example.com/stamp.apk"}"""

    @Test fun readsTheLatestRelease() {
        val r = Release.parse(release("v1.1.0", """{"name":"other.zip","size":1,"browser_download_url":"x"},$apk""", "## New\n- Updates from inside the app\n\n**Full Changelog**: x"))!!
        assertEquals("1.1.0", r.version)
        assertEquals("https://example.com/stamp.apk", r.apk)
        assertEquals(4200L, r.size)
        assertEquals("• Updates from inside the app", r.notes)
    }

    @Test fun dropsGeneratedCredits() {
        assertEquals(
            "• Legs follow the real rails\n• Plain line",
            Release.notes("## What's Changed\n* Legs follow the real rails by @someone in https://example.com/pull/40\n- Plain line\n\n**Full Changelog**: https://example.com"),
        )
    }

    @Test fun skipsReleasesWithoutAnApkAndDrafts() {
        assertNull(Release.parse(release("v1.1.0", "")))
        assertNull(Release.parse(release("v1.1.0", apk, draft = true)))
        assertNull(Release.parse("not json"))
    }
}
