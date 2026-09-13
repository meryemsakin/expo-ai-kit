package expo.modules.aikit.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Face detection on the upright image (EXIF applied). Returns the full image
 * size and raw face boxes in full-resolution upright pixels; the JS layer
 * normalizes and sorts.
 *
 * The detector runs on a downscaled decode, at most [DETECT_MAX_EDGE] px on
 * the long edge. ML Kit only finds faces wider than a fraction of the frame
 * (its minimum face size is relative, 10% of the image width by default), so
 * what is detectable does not change with the decode size, while a 12 MP photo
 * costs about 4 MB of pixels instead of 48 MB and runs several times faster.
 * Boxes are mapped back to full-resolution pixels before they leave here.
 *
 * One detector is shared across calls, so the model loads once per process
 * instead of once per photo, and the native call is serialized so overlapping
 * requests (apps often check several photos at once) never hold several
 * decoded bitmaps in ML Kit at the same time.
 */
class FaceDetectionClient(support: VisionSupport) : VisionFeatureClient(support), FaceFeature {

  companion object {
    private const val DETECT_MAX_EDGE = 1600
  }

  private class Decoded(val bitmap: Bitmap, val fullWidth: Int, val fullHeight: Int)

  private val detectorLock = Any()
  private var detector: FaceDetector? = null
  private val detectMutex = Mutex()

  private fun failure(code: String, cause: Exception) =
    RuntimeException("$code:${VisionSupport.MODEL_ID}:${cause.message ?: "Face detection failed"}", cause)

  private fun detectorClient(): FaceDetector =
    synchronized(detectorLock) {
      detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
          .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
          .build()
      ).also { detector = it }
    }

  /** A detector that failed may hold broken state; drop it so the next call rebuilds. */
  private fun discardDetector(stale: FaceDetector) {
    synchronized(detectorLock) {
      if (detector === stale) detector = null
    }
    try {
      stale.close()
    } catch (_: Throwable) {}
  }

  // The face models are bundled, no Play services module involved.
  override suspend fun availability(): Map<String, Any?> = mapOf("status" to "available")

  override suspend fun detectFaces(imageUri: String): Map<String, Any?> = withContext(Dispatchers.IO) {
    val image = try { loadImage(imageUri) } catch (e: Exception) {
      throw failure("IMAGE_DECODE_FAILED", e)
    }
    try {
      val faces = try {
        val client = detectorClient()
        try {
          detectMutex.withLock {
            // Wait for ML Kit to finish even if the caller is cancelled: it still owns the bitmap.
            suspendCoroutine<List<Face>> { continuation ->
              client.process(InputImage.fromBitmap(image.bitmap, 0))
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.resumeWithException(IllegalStateException("Face detection cancelled")) }
            }
          }
        } catch (e: Exception) {
          discardDetector(client)
          throw e
        }
      } catch (e: Exception) { throw failure("VISION_FAILED", e) }
      // Map boxes from the decoded size back to full-resolution upright pixels.
      val scaleX = image.fullWidth.toDouble() / image.bitmap.width
      val scaleY = image.fullHeight.toDouble() / image.bitmap.height
      mapOf(
        "width" to image.fullWidth.toDouble(),
        "height" to image.fullHeight.toDouble(),
        "faces" to faces.map { face ->
          val bounds = face.boundingBox
          mapOf(
            "x" to bounds.left * scaleX,
            "y" to bounds.top * scaleY,
            "width" to bounds.width() * scaleX,
            "height" to bounds.height() * scaleY
          )
        }
      )
    } finally { image.bitmap.recycle() }
  }

  private fun loadImage(imageUri: String): Decoded {
    val uri = Uri.parse(imageUri)
    fun openStream(): InputStream? = when (uri.scheme) {
      "file", null -> File(uri.path ?: imageUri).inputStream()
      "content" -> context.contentResolver.openInputStream(uri)
      else -> throw IllegalArgumentException("Face detection requires a local image")
    }

    // Header only: full size without decoding pixels.
    val header = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    // decodeStream returns null by design with inJustDecodeBounds; only the stream can fail here.
    (openStream() ?: throw IllegalArgumentException("Could not open the image"))
      .use { BitmapFactory.decodeStream(it, null, header) }
    val sourceWidth = header.outWidth
    val sourceHeight = header.outHeight
    if (sourceWidth <= 0 || sourceHeight <= 0) throw IllegalArgumentException("Could not decode the image")

    val orientation = openStream()?.use {
      ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val swapsAxes = when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
      ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
      else -> false
    }
    val fullWidth = if (swapsAxes) sourceHeight else sourceWidth
    val fullHeight = if (swapsAxes) sourceWidth else sourceHeight

    // Power-of-two subsampling for memory, then density scaling for the rest,
    // both inside the decoder so no full-size bitmap ever exists.
    val longest = maxOf(sourceWidth, sourceHeight)
    var sampleSize = 1
    while (longest / (sampleSize * 2) >= DETECT_MAX_EDGE) sampleSize *= 2
    val sampledLongest = (longest + sampleSize - 1) / sampleSize
    val options = BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
      inMutable = false
      if (sampledLongest > DETECT_MAX_EDGE) {
        inScaled = true
        inDensity = sampledLongest
        inTargetDensity = DETECT_MAX_EDGE
      }
    }
    val decoded = openStream()?.use { BitmapFactory.decodeStream(it, null, options) }
      ?: throw IllegalArgumentException("Could not decode the image")
    try {
      val matrix = Matrix()
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return Decoded(decoded, fullWidth, fullHeight)
      }
      val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
      if (upright !== decoded) decoded.recycle()
      return Decoded(upright, fullWidth, fullHeight)
    } catch (e: Exception) { decoded.recycle(); throw e }
  }
}
