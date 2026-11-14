package com.young.waterremote.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteeUpdateCheckerTest {
    @Test
    fun versionComparator_comparesNumericVersionSegments() {
        assertTrue(VersionComparator.isNewer("1.4.0", "1.3.7"))
        assertTrue(VersionComparator.isNewer("v1.10.0", "1.9.9"))
        assertFalse(VersionComparator.isNewer("1.3.7", "1.3.7"))
        assertFalse(VersionComparator.isNewer("1.3.6", "1.3.7"))
    }

    @Test
    fun parseRelease_selectsApkAsset() {
        val release = GiteeUpdateChecker().parseRelease(
            """
            {
              "tag_name": "v1.3.8",
              "assets": [
                {
                  "name": "v1.3.8.zip",
                  "browser_download_url": "https://gitee.com/owner/repo/archive/refs/tags/v1.3.8.zip"
                },
                {
                  "name": "RuanCunDrink-1.3.8-release.apk",
                  "browser_download_url": "https://gitee.com/owner/repo/releases/download/v1.3.8/app.apk"
                }
              ]
            }
            """.trimIndent(),
            "owner/repo"
        )

        assertEquals("1.3.8", release.versionName)
        assertEquals("https://gitee.com/owner/repo/releases/download/v1.3.8/app.apk", release.downloadUrl)
        assertEquals("https://gitee.com/owner/repo/releases/tag/v1.3.8", release.releaseUrl)
    }

    @Test
    fun fetchLatestReleaseUsesPublicGiteeApi() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://gitee.com/api/v5/repos/owner/repo/releases/latest", request.url.toString())
            assertEquals("application/json", request.header("Accept"))
            assertEquals(null, request.header("Authorization"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                .message("OK").body(releaseJson().toResponseBody()).build()
        }.build()

        assertEquals("1.3.27", GiteeUpdateChecker(client).fetchLatestRelease("owner/repo").versionName)
    }

    @Test
    fun incompleteMirrorDoesNotOfferAnApk() {
        assertRejected("""{"tag_name":"v1.3.27","assets":[]}""")
    }

    @Test
    fun rejectsGithubAndOtherDownloadHosts() {
        for (url in listOf(
            "https://github.com/owner/repo/releases/download/v1.3.27/app.apk",
            "https://gitee.com.example.com/app.apk",
            "http://gitee.com/owner/repo/app.apk"
        )) {
            assertRejected(releaseJson(url))
        }
    }

    @Test
    fun acceptsGiteeAttachmentDownloadUrls() {
        val url = "https://gitee.com/attachments/download/123456"
        assertEquals(url, GiteeUpdateChecker().parseRelease(releaseJson(url), "owner/repo").downloadUrl)
    }

    @Test
    fun rejectsPrereleasesAndInvalidMetadata() {
        assertRejected(releaseJson().replace("\"tag_name\"", "\"prerelease\":true,\"tag_name\""))
        for (body in listOf("not json", "[]", "{}", """{"tag_name":"v"}""")) {
            assertRejected(body)
        }
    }

    @Test
    fun invalidRepositoryDoesNotMakeANetworkRequest() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected request") }.build()
        for (repository in listOf("", "owner", "owner/repo/extra", "/repo", "../repo")) {
            try {
                GiteeUpdateChecker(client).fetchLatestRelease(repository)
                throw AssertionError("Expected invalid repository to fail")
            } catch (_: RemoteApiException) {
                // Invalid configuration is reported before attempting a connection.
            }
        }
    }

    @Test
    fun legacyGithubCachedDownloadIsNotAccepted() {
        assertFalse(isGiteeDownloadUrl("https://github.com/owner/repo/releases/download/v1/app.apk"))
        assertTrue(isGiteeDownloadUrl("https://gitee.com/owner/repo/releases/download/v1/app.apk"))
    }

    private fun releaseJson(url: String = "https://gitee.com/owner/repo/releases/download/v1.3.27/app.apk") =
        """{"tag_name":"v1.3.27","assets":[{"name":"app.apk","browser_download_url":"$url"}]}"""

    private fun assertRejected(body: String) {
        try {
            GiteeUpdateChecker().parseRelease(body, "owner/repo")
            throw AssertionError("Expected invalid release to fail")
        } catch (_: RemoteApiException) {
            // Never offer a download until the public Gitee APK is available.
        }
    }
}
