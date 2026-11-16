package dev.g5.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

data class ProcessedPhoto(
    val file: File,
    val uri: Uri,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Downscales incoming 13MP POV photos from the DPVR G5 glasses to 768x768 (JPEG quality 80)
 * in a background cache file and provides a secure FileProvider Uri.
 */
object PovImageProcessor {
    private const val TAG = "PovImageProcessor"
    const val TARGET_DIMENSION = 768
    const val JPEG_QUALITY = 80
    private const val CACHE_DIR_NAME = "pov_cache"
    private const val FILE_NAME = "pov_768.jpg"

    fun process(context: Context, rawBytes: ByteArray): ProcessedPhoto? {
        if (rawBytes.isEmpty()) return null
        return try {
            // 1. Decode bounds to compute inSampleSize
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            // 2. Decode with sub-sampling
            val maxDim = max(origWidth, origHeight)
            var sampleSize = 1
            while (maxDim / (sampleSize * 2) >= TARGET_DIMENSION) {
                sampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampledBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

            // 3. Exact downscaling to fit within 768x768 preserving aspect ratio
            val scale = TARGET_DIMENSION.toFloat() / max(sampledBitmap.width, sampledBitmap.height)
            val targetWidth = (sampledBitmap.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (sampledBitmap.height * scale).roundToInt().coerceAtLeast(1)

            val scaledBitmap = if (sampledBitmap.width != targetWidth || sampledBitmap.height != targetHeight) {
                Bitmap.createScaledBitmap(sampledBitmap, targetWidth, targetHeight, true).also {
                    if (it != sampledBitmap) sampledBitmap.recycle()
                }
            } else {
                sampledBitmap
            }

            // 4. Compress to JPEG quality 80
            val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
            val cacheFile = File(cacheDir, FILE_NAME)
            val outBytesStream = ByteArrayOutputStream()
            FileOutputStream(cacheFile).use { fos ->
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos)
            }
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outBytesStream)
            val processedBytes = outBytesStream.toByteArray()

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                cacheFile,
            )

            ProcessedPhoto(
                file = cacheFile,
                uri = uri,
                bytes = processedBytes,
                width = targetWidth,
                height = targetHeight,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error downscaling POV photo", e)
            null
        }
    }
}
