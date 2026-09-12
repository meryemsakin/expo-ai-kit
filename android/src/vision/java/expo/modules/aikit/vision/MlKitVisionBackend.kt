package expo.modules.aikit.vision

import android.content.Context
import expo.modules.aikit.VisionBackend

/**
 * ML Kit vision backend, compiled when the `vision` config-plugin option is
 * on. It owns no ML Kit client itself: each feature (subject segmentation,
 * image labeling, text recognition, face detection) lives in its own source
 * set that build.gradle compiles only when the option names it, and is
 * resolved here by reflection (constructors kept by consumer-rules.pro).
 *
 * Models are never downloaded implicitly: [prepare] installs the Play services
 * modules for segmentation and OCR, and those calls throw MODEL_NOT_DOWNLOADED
 * until it has. Labeling and face detection ship their models in the app.
 */
class MlKitVisionBackend(context: Context) : VisionBackend {

  companion object {
    const val MODEL_ID = "mlkit-vision"
    private const val PACKAGE = "expo.modules.aikit.vision"
  }

  private val support = VisionSupport(context)

  private fun feature(className: String): VisionFeatureClient? =
    try {
      Class.forName("$PACKAGE.$className")
        .getDeclaredConstructor(VisionSupport::class.java)
        .newInstance(support) as VisionFeatureClient
    } catch (e: ReflectiveOperationException) {
      null
    } catch (e: LinkageError) {
      null
    }

  private val segmentation by lazy { feature("SubjectSegmentationClient") }
  private val labeling by lazy { feature("ImageLabelingClient") }
  private val text by lazy { feature("TextRecognitionClient") }
  private val faces by lazy { feature("FaceDetectionClient") }

  private fun describe(feature: String): String = when (feature) {
    "background-removal" -> "Background removal"
    "image-labeling" -> "Image labeling"
    "text-recognition" -> "Text recognition"
    "face-detection" -> "Face detection"
    else -> support.fail("VISION_FAILED", "Unknown vision feature \"$feature\"")
  }

  private fun clientFor(feature: String): VisionFeatureClient? = when (feature) {
    "background-removal" -> segmentation
    "image-labeling" -> labeling
    "text-recognition" -> text
    "face-detection" -> faces
    else -> null
  }

  private fun notEnabled(feature: String): Nothing =
    support.fail(
      "VISION_NOT_ENABLED",
      describe(feature) + " is not compiled into this build. Add \"" + feature +
        "\" to [\"expo-ai-kit\", { \"vision\": [...] }] (or set \"vision\": true) in your app config " +
        "plugins and make a new native build (dev client / EAS, not OTA)."
    )

  private suspend fun availabilityOf(client: VisionFeatureClient?): Map<String, Any?> =
    client?.availability() ?: mapOf("status" to "unavailable", "reason" to "not-enabled")

  override suspend fun availability(): Map<String, Any?> = mapOf(
    "backgroundRemoval" to availabilityOf(segmentation),
    "imageLabeling" to availabilityOf(labeling),
    "textRecognition" to availabilityOf(text),
    "faceDetection" to availabilityOf(faces)
  )

  override suspend fun prepare(
    features: List<String>,
    languages: List<String>,
    onProgress: (Double) -> Unit
  ) {
    val clients = features.map { name ->
      describe(name)
      clientFor(name) ?: notEnabled(name)
    }
    if (clients.isEmpty()) {
      onProgress(1.0)
      return
    }
    // Sequential per feature; report combined progress across them.
    clients.forEachIndexed { index, client ->
      client.prepare(languages) { fraction ->
        onProgress(((index + fraction) / clients.size).coerceIn(0.0, 1.0))
      }
    }
    onProgress(1.0)
  }

  override suspend fun detectFaces(uri: String): Map<String, Any?> =
    (faces as? FaceFeature ?: notEnabled("face-detection")).detectFaces(uri)

  override suspend fun removeBackground(
    uri: String,
    trim: Boolean,
    format: String,
    quality: Double,
    maxPixels: Int,
    subjectX: Double,
    subjectY: Double,
    includeMask: Boolean
  ): Map<String, Any?> =
    (segmentation as? SegmentationFeature ?: notEnabled("background-removal"))
      .removeBackground(uri, trim, format, quality, maxPixels, subjectX, subjectY, includeMask)

  override suspend fun labelImage(uri: String, maxResults: Int, minConfidence: Double): List<Map<String, Any?>> =
    (labeling as? LabelingFeature ?: notEnabled("image-labeling"))
      .labelImage(uri, maxResults, minConfidence)

  override suspend fun recognizeText(uri: String, languages: List<String>, minTextHeight: Double): Map<String, Any?> =
    (text as? TextFeature ?: notEnabled("text-recognition"))
      .recognizeText(uri, languages, minTextHeight)
}
