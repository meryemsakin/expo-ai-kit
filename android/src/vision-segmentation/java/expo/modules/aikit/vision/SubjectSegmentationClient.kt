package expo.modules.aikit.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SubjectSegmentationClient(support: VisionSupport) : VisionFeatureClient(support), SegmentationFeature {
  companion object {
    private const val SEGMENT_MAX_EDGE = 512
    private const val FOREGROUND_THRESHOLD = 0.5f
  }

  private val play = PlayModules(support)
  private fun requirePlayServices(feature: String) = play.requirePlayServices(feature)
  private suspend fun requireModules(feature: String, apis: List<OptionalModuleApi>) = play.requireModules(feature, apis)

  private val segmenterLock = Any()
  private var segmenter: SubjectSegmenter? = null
  // Play services' segmentation library keeps touching the pixel memory of
  // the bitmap it was last given after its Task resolves: recycling that
  // bitmap right away made the *next* process() SIGSEGV inside the library
  // (read of unmapped memory on its GL and executor threads, Galaxy A16,
  // Sep 2026). So the native call is serialized, and the input bitmap stays
  // alive until a later segmentation has completed; only then is it recycled.
  private val segmentMutex = Mutex()
  private var previousSegmenterInput: Bitmap? = null

  private fun segmenterClient(): SubjectSegmenter =
    synchronized(segmenterLock) {
      segmenter ?: SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder()
          .enableForegroundConfidenceMask()
          // Per-subject masks let the caller keep only the subject under a point.
          .enableMultipleSubjects(
            SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build()
          )
          .build()
      ).also { segmenter = it }
    }

  private fun discardSegmenter(stale: SubjectSegmenter) {
    synchronized(segmenterLock) {
      if (segmenter === stale) segmenter = null
    }
    try {
      stale.close()
    } catch (_: Throwable) {}
  }

  override suspend fun availability(): Map<String, Any?> = withContext(Dispatchers.IO) {
    if (!play.playServicesAvailable()) {
      return@withContext mapOf("status" to "unavailable", "reason" to "device")
    }
    val status = try {
      if (play.modulesInstalled(listOf(segmenterClient()))) "available" else "downloadable"
    } catch (_: Throwable) {
      "downloadable"
    }
    mapOf("status" to status)
  }

  override suspend fun prepare(languages: List<String>, onProgress: (Double) -> Unit) = withContext(Dispatchers.IO) {
    requirePlayServices("Background removal")
    wrapping("DOWNLOAD_FAILED") { play.installModules(listOf(segmenterClient()), onProgress) }
  }

  // ==================================================================
  // Background removal (subject cutout)
  // ==================================================================

  override suspend fun removeBackground(
    uri: String,
    trim: Boolean,
    format: String,
    quality: Double,
    maxPixels: Int,
    subjectX: Double,
    subjectY: Double,
    includeMask: Boolean
  ): Map<String, Any?> = withContext(Dispatchers.Default) {
    requirePlayServices("Background removal")
    val client = segmenterClient()
    requireModules("subject segmentation", listOf(client))

    val source = loadBitmap(uri, maxPixels)
    try {
      // Always hand the segmenter its own bitmap (never `source`, which is
      // recycled below) so its lifetime can outlive this call, see segmentMutex.
      val scaled = mlKitInput(source, SEGMENT_MAX_EDGE)
      val input = if (scaled !== source) scaled else source.copy(Bitmap.Config.ARGB_8888, false)
      val result = try {
        segmentMutex.withLock {
          val r = wrapping("VISION_FAILED") { client.process(InputImage.fromBitmap(input, 0)).await() }
          // The previous input is safe to free now that a later call finished.
          previousSegmenterInput?.recycle()
          previousSegmenterInput = input
          r
        }
      } catch (e: Throwable) {
        // A client built before the module finished installing keeps a failed
        // init for the life of the process; drop it so the next call rebuilds.
        if (e !is CancellationException) discardSegmenter(client)
        throw e
      }
      val maskWidth = input.width
      val maskHeight = input.height
      val subjects = result.subjects
      val mask: FloatArray
      if (subjectX >= 0 && subjectY >= 0) {
        // Keep only the subject whose mask is most confident under the point.
        val px = (subjectX * maskWidth).toInt().coerceIn(0, maskWidth - 1)
        val py = (subjectY * maskHeight).toInt().coerceIn(0, maskHeight - 1)
        var best: com.google.mlkit.vision.segmentation.subject.Subject? = null
        var bestConfidence = FOREGROUND_THRESHOLD
        for (subject in subjects) {
          val buffer = subject.confidenceMask ?: continue
          val localX = px - subject.startX
          val localY = py - subject.startY
          if (localX < 0 || localY < 0 || localX >= subject.width || localY >= subject.height) continue
          val confidence = buffer.get(localY * subject.width + localX)
          if (confidence > bestConfidence) {
            bestConfidence = confidence
            best = subject
          }
        }
        val chosen = best ?: fail("NO_SUBJECT_FOUND", "No subject under the selected point")
        mask = FloatArray(maskWidth * maskHeight)
        val buffer = chosen.confidenceMask!!
        buffer.rewind()
        for (y in 0 until chosen.height) {
          val targetY = chosen.startY + y
          if (targetY < 0 || targetY >= maskHeight) continue
          for (x in 0 until chosen.width) {
            val targetX = chosen.startX + x
            if (targetX < 0 || targetX >= maskWidth) continue
            mask[targetY * maskWidth + targetX] = buffer.get(y * chosen.width + x)
          }
        }
      } else {
        val rawMask = result.foregroundConfidenceMask
          ?: fail("NO_SUBJECT_FOUND", "No foreground subject detected in the image")
        mask = FloatArray(maskWidth * maskHeight)
        rawMask.rewind()
        rawMask.get(mask)
      }

      val width = source.width
      val height = source.height
      val colors = IntArray(width * height)
      source.getPixels(colors, 0, width, 0, 0, width, height)

      // Apply the (nearest-neighbour upscaled) confidence mask as alpha and
      // measure the foreground in the same pass. Keep the upscaled mask when the
      // caller wants it written out.
      val fullMask = if (includeMask) FloatArray(width * height) else null
      var foreground = 0
      var sumX = 0.0
      var sumY = 0.0
      var minX = width
      var minY = height
      var maxX = -1
      var maxY = -1
      for (y in 0 until height) {
        val my = minOf(maskHeight - 1, y * maskHeight / height)
        for (x in 0 until width) {
          val mx = minOf(maskWidth - 1, x * maskWidth / width)
          val confidence = mask[my * maskWidth + mx].coerceIn(0f, 1f)
          val i = y * width + x
          colors[i] = ((confidence * 255f).toInt() shl 24) or (colors[i] and 0x00FFFFFF)
          fullMask?.set(i, confidence)
          if (confidence > FOREGROUND_THRESHOLD) {
            foreground++
            sumX += x
            sumY += y
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
          }
        }
      }
      if (foreground == 0 || maxX < 0) {
        fail("NO_SUBJECT_FOUND", "No foreground subject detected in the image")
      }
      val boundsX = minX
      val boundsY = minY
      val boundsW = maxX - minX + 1
      val boundsH = maxY - minY + 1

      var cropX = 0
      var cropY = 0
      var cropW = width
      var cropH = height
      if (trim) {
        val pad = 2
        cropX = (boundsX - pad).coerceAtLeast(0)
        cropY = (boundsY - pad).coerceAtLeast(0)
        val right = (boundsX + boundsW - 1 + pad).coerceAtMost(width - 1)
        val bottom = (boundsY + boundsH - 1 + pad).coerceAtMost(height - 1)
        cropW = right - cropX + 1
        cropH = bottom - cropY + 1
      }
      val cutout = if (cropX == 0 && cropY == 0 && cropW == width && cropH == height) {
        Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
      } else {
        Bitmap.createBitmap(colors, cropY * width + cropX, width, cropW, cropH, Bitmap.Config.ARGB_8888)
      }
      try {
        val jpeg = format == "jpeg"
        val file = outputFile(if (jpeg) "jpg" else "png")
        wrapping("VISION_FAILED") {
          if (jpeg) {
            // JPEG has no alpha: flatten onto white, like iOS.
            val flat = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
            try {
              val canvas = Canvas(flat)
              canvas.drawColor(Color.WHITE)
              canvas.drawBitmap(cutout, 0f, 0f, null)
              FileOutputStream(file).use { out ->
                flat.compress(Bitmap.CompressFormat.JPEG, (quality.coerceIn(0.0, 1.0) * 100).toInt(), out)
              }
            } finally {
              flat.recycle()
            }
          } else {
            FileOutputStream(file).use { out -> cutout.compress(Bitmap.CompressFormat.PNG, 100, out) }
          }
        }
        val maskUri = fullMask?.let { writeMask(it, width, cropX, cropY, cropW, cropH) }
        val w = width.toDouble()
        val h = height.toDouble()
        buildMap<String, Any?> {
          put("uri", Uri.fromFile(file).toString())
          if (maskUri != null) put("maskUri", maskUri)
          putAll(mapOf(
          "width" to cropW,
          "height" to cropH,
          "sourceWidth" to width,
          "sourceHeight" to height,
          "bounds" to mapOf(
            "x" to boundsX / w,
            "y" to boundsY / h,
            "width" to boundsW / w,
            "height" to boundsH / h
          ),
          "pixelBounds" to mapOf("x" to boundsX, "y" to boundsY, "width" to boundsW, "height" to boundsH),
          "foregroundCoverage" to foreground.toDouble() / (w * h),
          "centroid" to mapOf("x" to (sumX / foreground + 0.5) / w, "y" to (sumY / foreground + 0.5) / h),
          "instanceCount" to if (subjects.isNotEmpty()) subjects.size else 1,
          "trimOrigin" to mapOf("x" to cropX / w, "y" to cropY / h)
          ))
        }
      } finally {
        cutout.recycle()
      }
    } finally {
      source.recycle()
    }
  }

  /** Write the confidence mask for the crop rect as an 8-bit grayscale PNG (white = subject). */
  private fun writeMask(mask: FloatArray, stride: Int, cropX: Int, cropY: Int, cropW: Int, cropH: Int): String {
    val pixels = IntArray(cropW * cropH)
    for (y in 0 until cropH) {
      for (x in 0 until cropW) {
        val v = (mask[(cropY + y) * stride + cropX + x].coerceIn(0f, 1f) * 255f).toInt()
        pixels[y * cropW + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
      }
    }
    val bitmap = Bitmap.createBitmap(pixels, cropW, cropH, Bitmap.Config.ARGB_8888)
    try {
      val file = outputFile("png")
      wrapping("VISION_FAILED") {
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
      }
      return Uri.fromFile(file).toString()
    } finally {
      bitmap.recycle()
    }
  }

}
