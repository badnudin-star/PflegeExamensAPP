package de.pflegeexamen.app.data.content

import android.content.Context
import android.util.AtomicFile
import de.pflegeexamen.app.BuildConfig
import de.pflegeexamen.app.domain.content.LearningCatalog
import de.pflegeexamen.app.domain.content.CatalogUpdatePolicy
import de.pflegeexamen.app.domain.content.LearningCatalogCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Read-only published content. No secret or editable service URL is stored in the app. */
class PublishedContentStore(private val context: Context) {
    private val file = AtomicFile(File(context.filesDir, "published-learning.json"))
    private val lock = Mutex()
    private val bundled = runCatching { context.assets.open("learning_content.json").bufferedReader().use { it.readText() } }.getOrDefault("")
    private val bundledCatalog = LearningCatalogCodec.parse(bundled) ?: LearningCatalog()
    // Optional private build-time bundle, kept out of Git. Invalid/missing bundles are ignored.
    private val privateBundle = runCatching {
        context.assets.open("private/learning_content.json").bufferedReader().use { it.readText() }
    }.getOrNull()?.let(LearningCatalogCodec::parse)
    private val packagedCatalog = privateBundle?.takeIf { CatalogUpdatePolicy.canUseAtStartup(bundledCatalog, it) } ?: bundledCatalog
    private val initial = runCatching { file.openRead().bufferedReader().use { it.readText() } }.getOrNull()
        ?.let(LearningCatalogCodec::parse)?.takeIf { CatalogUpdatePolicy.canUseAtStartup(packagedCatalog, it) } ?: packagedCatalog
    private val _catalog = MutableStateFlow(initial)
    val catalog: StateFlow<LearningCatalog> = _catalog.asStateFlow()

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!CONTENT_URL.startsWith("https://")) return@withLock false
            var connection: HttpURLConnection? = null
            try {
                connection = URL(CONTENT_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                if (connection.responseCode != 200) return@withLock false
                val raw = connection.inputStream.use { input ->
                    val bytes = input.readBytesLimited(MAX_CONTENT_BYTES) ?: return@withLock false
                    bytes.toString(Charsets.UTF_8)
                }
                val next = LearningCatalogCodec.parse(raw) ?: return@withLock false
                if (!CatalogUpdatePolicy.canRefresh(_catalog.value, next)) return@withLock false
                val output = file.startWrite()
                try {
                    output.write(raw.toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (e: Exception) {
                    file.failWrite(output)
                    throw e
                }
                _catalog.value = next
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    companion object {
        // Set the public HTTPS endpoint at build/deployment time after the Worker exists.
        // Privileged API tokens must stay on the server and never be bundled into the APK.
        val CONTENT_URL: String = BuildConfig.CONTENT_URL
        private const val MAX_CONTENT_BYTES = 8 * 1024 * 1024
    }
}

private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > max) return null
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
