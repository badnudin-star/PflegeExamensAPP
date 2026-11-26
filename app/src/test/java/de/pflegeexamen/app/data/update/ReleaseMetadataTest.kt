package de.pflegeexamen.app.data.update

import org.junit.Assert.*
import org.junit.Test

class ReleaseMetadataTest {
    private fun release(name: String = "PflegeExamen-vc20.apk", tag: String = "v3.0.9",
                        url: String = "https://github.com/badnudin-star/PflegeExamensAPP/releases/download/$tag/$name",
                        size: Long = 123, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"assets":[{"name":"$name","size":$size,"browser_download_url":"$url"}]}"""

    @Test fun acceptsOnlyExactlyMappedStableRelease() {
        val asset = ReleaseMetadata.parse(release())!!
        assertEquals(20L, asset.versionCode)
        assertEquals("3.0.9", asset.versionName)
        assertEquals(123L, asset.size)
        assertNull(ReleaseMetadata.parse(release(draft = true)))
        assertNull(ReleaseMetadata.parse(release(prerelease = true)))
        assertNull(ReleaseMetadata.parse(release(name = "other.apk")))
        assertNull(ReleaseMetadata.parse(release(tag = "v3.0.9-rc1")))
        assertNull(ReleaseMetadata.parse(release(url = "https://evil.example/app.apk")))
        assertNull(ReleaseMetadata.parse(release(size = 0)))
        assertNull(ReleaseMetadata.parse(release(size = ReleaseMetadata.MAX_APK + 1)))
        assertNull(ReleaseMetadata.parse("not json"))
        assertNull(ReleaseMetadata.parse("""{"tag_name":"v3.0.9","draft":false,"prerelease":false,"assets":[]}"""))
    }

    @Test fun redirectsRequireHttpsAndExactAllowedHosts() {
        assertTrue(ReleaseMetadata.allowedDownloadUrl("https://release-assets.githubusercontent.com/path?temporary=token"))
        assertTrue(ReleaseMetadata.allowedDownloadUrl("https://github.com/safe"))
        assertFalse(ReleaseMetadata.allowedDownloadUrl("http://github.com/path"))
        assertFalse(ReleaseMetadata.allowedDownloadUrl("https://github.com.evil.example/path"))
        assertFalse(ReleaseMetadata.allowedDownloadUrl("https://github.com@evil.example/path"))
        assertFalse(ReleaseMetadata.allowedDownloadUrl("https://github.com:444/path"))
        assertFalse(ReleaseMetadata.allowedDownloadUrl("https://github.com/path#fragment"))
    }
}
