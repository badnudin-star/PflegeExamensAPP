package de.pflegeexamen.app.data.ingestion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 100 % Offline-Texterkennung (OCR) über das gebündelte Google ML Kit Latin-Modell.
 * Ermöglicht das Einscannen von Lehrbuchseiten, Arbeitsblättern und Mitschriften
 * per Kamera oder Galerie ohne Internetverbindung.
 */
class OcrProcessor(private val context: Context) {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognizeFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val bitmap = decodeOrientedBitmap(uri)
            ?: throw IllegalArgumentException("Bild konnte nicht geladen werden.")
        try {
            recognizeFromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun recognizeFromBitmap(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (!cont.isActive) return@addOnSuccessListener
                val structuredText = visionText.textBlocks.joinToString("\n\n") { block ->
                    block.lines.joinToString("\n") { it.text }
                }.trim()
                cont.resume(structuredText.ifBlank { visionText.text.trim() })
            }
            .addOnFailureListener { exc ->
                if (cont.isActive) cont.resumeWithException(exc)
            }
    }

    private fun decodeOrientedBitmap(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        // Skaliere sehr große Kamera-Fotos behutsam auf max. ~1800px Kantenlänge für scharfe OCR ohne OOM
        val maxDim = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 1800) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBitmap = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: return null

        val rotationDegrees = resolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (rotationDegrees == 0f) return rawBitmap

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
        if (rotated != rawBitmap) rawBitmap.recycle()
        return rotated
    }
}
