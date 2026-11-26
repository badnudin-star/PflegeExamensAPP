package de.pflegeexamen.app.data.update

import kotlinx.serialization.json.*
import java.net.URI

/** Only public, stable releases from this exact repository are eligible. */
internal data class ReleaseAsset(val versionCode: Long, val versionName: String, val url: String, val size: Long)

internal object ReleaseMetadata {
    const val API_URL = "https://api.github.com/repos/badnudin-star/PflegeExamensAPP/releases/latest"
    const val MAX_APK = 200L * 1024 * 1024
    private val namePattern = Regex("PflegeExamen-vc([1-9][0-9]{0,8})\\.apk")
    private val tagPattern = Regex("v[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")

    fun parse(raw: String): ReleaseAsset? = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        if (obj["draft"]?.jsonPrimitive?.booleanOrNull != false || obj["prerelease"]?.jsonPrimitive?.booleanOrNull != false) return null
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        if (!tagPattern.matches(tag)) return null
        val assets = obj["assets"]?.jsonArray ?: return null
        val matches = assets.mapNotNull { item ->
            val asset = item.jsonObject
            val name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val code = namePattern.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val expected = "https://github.com/badnudin-star/PflegeExamensAPP/releases/download/$tag/$name"
            if (size !in 1..MAX_APK || url != expected) return@mapNotNull null
            ReleaseAsset(code, tag.removePrefix("v"), url, size)
        }
        matches.singleOrNull()
    }.getOrNull()

    fun allowedDownloadUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.host?.lowercase() in setOf(
                "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }.getOrDefault(false)
}
