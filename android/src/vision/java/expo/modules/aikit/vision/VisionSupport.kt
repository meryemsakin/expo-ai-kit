package expo.modules.aikit.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException

/**
 * Shared by every compiled-in vision feature: the typed error contract and
 * image decoding. References no ML Kit class, so it compiles whatever subset
 * of features the `vision` option selected.
 *
 * Bitmaps handed to ML Kit are immutable ARGB_8888 software copies with the
 * longest edge capped (mutable/hardware bitmaps can crash across the Play
 * services binder boundary).
 */
class VisionSupport(val context: Context) {

  companion object {
    const val MODEL_ID = MlKitVisionBackend.MODEL_ID
  }

  fun fail(code: String, reason: String): Nothing =
    throw RuntimeException("$code:$MODEL_ID:$reason")

  fun isContract(e: Throwable): Boolean =
    e.message?.let { Regex("^[A-Z][A-Z_]*:").containsMatchIn(it) } == true

  /** Re-throw contract errors untouched; wrap anything else as [code]. */
  inline fun <T> wrapping(code: String, block: () -> T): T =
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: RuntimeException) {
      if (isContract(e)) throw e
      fail(code, e.message ?: e.toString())
    } catch (e: Throwable) {
      fail(code, e.message ?: e.toString())
    }

  // ==================================================================
  // Image loading
  // ==================================================================

  fun toUri(path: String): Uri =
    if (path.startsWith("file://") || path.startsWith("content://")) Uri.parse(path)
    else Uri.fromFile(File(path))

  /**
   * Decode [path] as an immutable software ARGB_8888 bitmap, upright (EXIF
   * baked in) and downscaled so width × height stays within [maxPixels].
   */
  fun loadBitmap(path: String, maxPixels: Int): Bitmap {
    val uri = toUri(path)
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
          val total = info.size.width.toLong() * info.size.height.toLong()
          if (total > maxPixels) {
            val scale = sqrt(total.toDouble() / maxPixels.toDouble())
            decoder.setTargetSize(
              (info.size.width / scale).toInt().coerceAtLeast(1),
              (info.size.height / scale).toInt().coerceAtLeast(1)
            )
          }
          decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        toImmutableArgb(decoded)
      } else {
        decodeLegacy(uri, maxPixels)
      }
    } catch (e: RuntimeException) {
      if (isContract(e)) throw e
      fail("IMAGE_DECODE_FAILED", "Could not decode the image at $path: ${e.message}")
    } catch (e: Throwable) {
      fail("IMAGE_DECODE_FAILED", "Could not decode the image at $path: ${e.message}")
    }
  }

  fun toImmutableArgb(bitmap: Bitmap): Bitmap {
    if (!bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
    val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
      ?: fail("IMAGE_DECODE_FAILED", "Could not copy the decoded image")
    if (copy !== bitmap) bitmap.recycle()
    return copy
  }

  private fun decodeLegacy(uri: Uri, maxPixels: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sampleSize = 1
    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
      var total = bounds.outWidth.toLong() * bounds.outHeight.toLong()
      while (total / (sampleSize.toLong() * sampleSize) > maxPixels) sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inSampleSize = sampleSize
    }
    val decoded = context.contentResolver.openInputStream(uri)?.use {
      BitmapFactory.decodeStream(it, null, options)
    } ?: fail("IMAGE_DECODE_FAILED", "Could not decode the image at $uri")
    // BitmapFactory ignores EXIF orientation (ImageDecoder on API 28+ bakes it in).
    val orientation = try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        ExifInterface(stream).getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_NORMAL
        )
      } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Throwable) {
      ExifInterface.ORIENTATION_NORMAL
    }
    return toImmutableArgb(applyExifOrientation(decoded, orientation))
  }

  private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.preRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.preRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.preRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.preRotate(90f)
        matrix.preScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.preRotate(270f)
        matrix.preScale(-1f, 1f)
      }
      else -> return bitmap
    }
    val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (oriented !== bitmap) bitmap.recycle()
    return oriented
  }

  /** Downscale to [maxEdge] and make sure the result is an immutable ARGB_8888 software bitmap. */
  fun mlKitInput(bitmap: Bitmap, maxEdge: Int): Bitmap {
    val longest = maxOf(bitmap.width, bitmap.height)
    val scaled = if (longest > maxEdge) {
      val scale = maxEdge.toFloat() / longest.toFloat()
      Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true
      )
    } else {
      bitmap
    }
    val needsCopy = scaled.isMutable || scaled.config != Bitmap.Config.ARGB_8888 ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && scaled.config == Bitmap.Config.HARDWARE)
    if (!needsCopy) return scaled
    val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
      ?: fail("IMAGE_DECODE_FAILED", "Could not prepare the image for ML Kit")
    if (scaled !== bitmap) scaled.recycle()
    return copy
  }

  fun outputFile(extension: String): File {
    val directory = File(context.cacheDir, "expo-ai-kit/vision")
    directory.mkdirs()
    return File(directory, "${UUID.randomUUID()}.$extension")
  }

}
