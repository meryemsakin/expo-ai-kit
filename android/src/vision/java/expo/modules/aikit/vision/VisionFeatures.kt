package expo.modules.aikit.vision

import android.graphics.Bitmap
import java.io.File

/**
 * One class per vision feature, each in its own source set that build.gradle
 * compiles only when the `vision` option names that feature (or is `true`).
 * MlKitVisionBackend resolves them by reflection, so a feature that was not
 * compiled in simply reports `not-enabled` and its call throws
 * VISION_NOT_ENABLED.
 */
abstract class VisionFeatureClient(protected val support: VisionSupport) {
  protected val context get() = support.context
  protected fun fail(code: String, reason: String): Nothing = support.fail(code, reason)
  protected fun isContract(e: Throwable): Boolean = support.isContract(e)
  protected inline fun <T> wrapping(code: String, block: () -> T): T = support.wrapping(code, block)
  protected fun loadBitmap(path: String, maxPixels: Int): Bitmap = support.loadBitmap(path, maxPixels)
  protected fun mlKitInput(bitmap: Bitmap, maxEdge: Int): Bitmap = support.mlKitInput(bitmap, maxEdge)
  protected fun outputFile(extension: String): File = support.outputFile(extension)

  /** 'available' | 'downloadable' | 'unavailable' (+ reason) for this feature on this device. */
  abstract suspend fun availability(): Map<String, Any?>

  /** Install anything this feature needs (Play services modules); no-op for bundled models. */
  open suspend fun prepare(languages: List<String>, onProgress: (Double) -> Unit) {
    onProgress(1.0)
  }
}

interface FaceFeature {
  suspend fun detectFaces(uri: String): Map<String, Any?>
}

interface LabelingFeature {
  suspend fun labelImage(uri: String, maxResults: Int, minConfidence: Double): List<Map<String, Any?>>
}

interface SegmentationFeature {
  suspend fun removeBackground(
    uri: String,
    trim: Boolean,
    format: String,
    quality: Double,
    maxPixels: Int,
    subjectX: Double,
    subjectY: Double,
    includeMask: Boolean
  ): Map<String, Any?>
}

interface TextFeature {
  suspend fun recognizeText(uri: String, languages: List<String>, minTextHeight: Double): Map<String, Any?>
}
