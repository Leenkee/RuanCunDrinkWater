package com.young.waterremote.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AvailableRelease(
    val versionName: String,
    val downloadUrl: String,
    val releaseUrl: String
)

class GiteeUpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun fetchLatestRelease(repository: String): AvailableRelease =
        withContext(Dispatchers.IO) {
            val parts = repositoryParts(repository)
            val url = GITEE_API.toHttpUrl().newBuilder()
                .addPathSegment("repos")
                .addPathSegment(parts[0])
                .addPathSegment(parts[1])
                .addPathSegment("releases")
                .addPathSegment("latest")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "RuanCunDrink-Android")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val message = if (response.code == 404) {
                            "尚未发布可用的新版本"
                        } else {
                            "检查更新失败（${response.code}）"
                        }
                        throw RemoteApiException(message)
                    }
                    parseRelease(body, repository)
                }
            } catch (error: RemoteApiException) {
                throw error
            } catch (error: IOException) {
                throw RemoteApiException("无法连接 Gitee，请检查网络", error)
            }
        }

    internal fun parseRelease(body: String, repository: String): AvailableRelease {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw RemoteApiException("Gitee 返回了无法识别的数据", it) }
        if (root.stringValue("prerelease")?.equals("true", ignoreCase = true) == true) {
            throw RemoteApiException("尚未发布可用的正式版本")
        }
        val tagName = root.stringValue("tag_name")?.takeIf { it.isNotBlank() }
            ?: throw RemoteApiException("最新版本没有版本号")
        val versionName = tagName.removePrefix("v").removePrefix("V")
            .takeIf { it.isNotBlank() } ?: throw RemoteApiException("最新版本没有版本号")
        val parts = repositoryParts(repository)
        val releaseUrl = "https://gitee.com".toHttpUrl().newBuilder()
            .addPathSegment(parts[0])
            .addPathSegment(parts[1])
            .addPathSegment("releases")
            .addPathSegment("tag")
            .addPathSegment(tagName)
            .build().toString()
        val downloadUrl = root.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
            ?.firstOrNull { asset ->
                asset.stringValue("name")?.endsWith(".apk", ignoreCase = true) == true &&
                    asset.stringValue("browser_download_url")?.let(::isGiteeDownloadUrl) == true
            }
            ?.stringValue("browser_download_url")
            ?: throw RemoteApiException("最新版本尚未同步可下载的 Gitee APK")

        return AvailableRelease(versionName, downloadUrl, releaseUrl)
    }

    private fun repositoryParts(repository: String): List<String> {
        val parts = repository.trim().split('/')
        if (parts.size != 2 || parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw RemoteApiException("更新服务尚未配置")
        }
        return parts
    }

    private fun JsonObject.stringValue(name: String): String? {
        val value = get(name) ?: return null
        if (!value.isJsonPrimitive || value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }

    private companion object {
        const val GITEE_API = "https://gitee.com/api/v5"
    }
}

internal fun isGiteeDownloadUrl(value: String): Boolean {
    val url = value.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.host == "gitee.com" && url.encodedPath != "/"
}

internal object VersionComparator {
    fun isNewer(latest: String, current: String): Boolean {
        val latestParts = numericParts(latest)
        val currentParts = numericParts(current)
        val length = maxOf(latestParts.size, currentParts.size)
        repeat(length) { index ->
            val latestValue = latestParts.getOrElse(index) { 0 }
            val currentValue = currentParts.getOrElse(index) { 0 }
            if (latestValue != currentValue) return latestValue > currentValue
        }
        return false
    }

    private fun numericParts(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .removePrefix("V")
        .substringBefore('-')
        .split('.')
        .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
}
