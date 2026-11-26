package de.pflegeexamen.app.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

private const val PDF_DIR = "private/note_pdfs"
internal fun notePdfName(source: String): String? {
    val name = source.removePrefix("Lernzettel: ")
    return name.takeIf { source.startsWith("Lernzettel: ") && it.matches(Regex("[A-Za-z0-9_]+\\.pdf")) }
}

internal fun noteTextDigest(body: String): String = MessageDigest.getInstance("SHA-256")
    .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

private sealed interface PdfPageResult {
    data object Loading : PdfPageResult
    data object Unavailable : PdfPageResult
    data class Page(val bitmap: Bitmap, val index: Int, val total: Int) : PdfPageResult
}

/** Only show a PDF if it is the exact source of the text currently on screen. */
private fun renderPage(context: Context, source: String, body: String, index: Int): PdfPageResult {
    val name = notePdfName(source) ?: return PdfPageResult.Unavailable
    val manifest = context.assets.open("$PDF_DIR/manifest.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    val digest = Json.parseToJsonElement(manifest).jsonObject[name]?.jsonPrimitive?.content
    if (digest != noteTextDigest(body)) return PdfPageResult.Unavailable
    val tmp = File.createTempFile("note-pdf-", ".pdf", context.cacheDir)
    try {
        context.assets.open("$PDF_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
        ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount !in 1..100 || index !in 0 until renderer.pageCount) return PdfPageResult.Unavailable
                renderer.openPage(index).use { page ->
                    if (page.width <= 0 || page.height <= 0) return PdfPageResult.Unavailable
                    val scale = minOf(1200.0 / page.width, 2000.0 / page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return PdfPageResult.Page(bitmap, index, renderer.pageCount)
                }
            }
        }
    } finally {
        tmp.delete()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotePdfDialog(source: String, body: String, dismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var index by rememberSaveable(source, body) { mutableIntStateOf(0) }
    val result by produceState<PdfPageResult>(PdfPageResult.Loading, source, body, index) {
        value = PdfPageResult.Loading
        value = withContext(Dispatchers.IO) {
            try { renderPage(context, source, body, index) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { PdfPageResult.Unavailable }
        }
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TopAppBar(title = { Text("Original-Lernzettel") }, navigationIcon = {
                    IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Schließen") }
                })
                when (val page = result) {
                    PdfPageResult.Loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    PdfPageResult.Unavailable -> Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Das passende Original-PDF ist in dieser App-Version nicht verfügbar. Der Text bleibt weiterhin lesbar.")
                    }
                    is PdfPageResult.Page -> {
                        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                            Image(page.bitmap.asImageBitmap(), contentDescription = "PDF-Seite ${page.index + 1} von ${page.total}", modifier = Modifier.fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { index-- }, enabled = page.index > 0) { Text("Zurück") }
                            Text("${page.index + 1} / ${page.total}")
                            TextButton(onClick = { index++ }, enabled = page.index + 1 < page.total) { Text("Weiter") }
                        }
                    }
                }
            }
        }
    }
}
