package com.young.waterremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateCheckerTest {
    @Test
    fun versionComparator_comparesNumericVersionSegments() {
        assertTrue(VersionComparator.isNewer("1.4.0", "1.3.7"))
        assertTrue(VersionComparator.isNewer("v1.10.0", "1.9.9"))
        assertFalse(VersionComparator.isNewer("1.3.7", "1.3.7"))
        assertFalse(VersionComparator.isNewer("1.3.6", "1.3.7"))
    }

    @Test
    fun parseRelease_selectsApkAsset() {
        val release = GitHubUpdateChecker().parseRelease(
            """
            {
              "tag_name": "v1.3.8",
              "html_url": "https://github.com/owner/repo/releases/tag/v1.3.8",
              "assets": [
                {
                  "name": "ShanRuanDrink-1.3.8-release.apk",
                  "browser_download_url": "https://example.com/app.apk"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("1.3.8", release.versionName)
        assertEquals("https://example.com/app.apk", release.downloadUrl)
    }
}
