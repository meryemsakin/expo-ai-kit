package expo.modules.aikit.vision

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ImageLabelingClient(support: VisionSupport) : VisionFeatureClient(support), LabelingFeature {
  companion object {
    private const val LABELING_MAX_PIXELS = 1_000_000
    private const val LABELING_MAX_EDGE = 512
  }

  // The label model is bundled, no Play services module involved.
  override suspend fun availability(): Map<String, Any?> = mapOf("status" to "available")

  // ==================================================================
  // Image labels
  // ==================================================================

  override suspend fun labelImage(
    uri: String,
    maxResults: Int,
    minConfidence: Double
  ): List<Map<String, Any?>> = withContext(Dispatchers.Default) {
    val source = loadBitmap(uri, LABELING_MAX_PIXELS)
    try {
      val input = mlKitInput(source, LABELING_MAX_EDGE)
      val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
          .setConfidenceThreshold(minConfidence.toFloat().coerceIn(0f, 1f))
          .build()
      )
      try {
        val labels = wrapping("VISION_FAILED") { labeler.process(InputImage.fromBitmap(input, 0)).await() }
        val sorted = labels.sortedByDescending { it.confidence }
        val limited = if (maxResults > 0) sorted.take(maxResults) else sorted
        limited.map { mapOf("label" to it.text, "confidence" to it.confidence.toDouble()) }
      } finally {
        try {
          labeler.close()
        } catch (_: Throwable) {}
        if (input !== source) input.recycle()
      }
    } finally {
      source.recycle()
    }
  }

}
