package expo.modules.aikit.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-resolution detection from expo-face-check 0.2.2; each call owns its detector and bitmap. */
internal class FaceCheckClient(private val context: Context) {
  private fun failure(code: String, cause: Exception) =
    RuntimeException("$code:mlkit-vision:${cause.message ?: "Face check failed"}", cause)

  suspend fun detectFaces(imageUri: String, minPixelSize: Double): Map<String, Any?> = withContext(Dispatchers.IO) {
    val bitmap = try { loadImage(imageUri) } catch (e: Exception) {
      throw failure("IMAGE_DECODE_FAILED", e)
    }
    try {
      val width = bitmap.width.toDouble()
      val height = bitmap.height.toDouble()
      if (width * height < minPixelSize) {
        return@withContext mapOf("width" to width, "height" to height, "faces" to emptyList<Any>())
      }
      val faces = try {
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
          .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        try {
          // Wait for ML Kit to finish even if the caller is cancelled: it still owns the bitmap.
          suspendCoroutine<List<Face>> { continuation ->
            detector.process(InputImage.fromBitmap(bitmap, 0))
              .addOnSuccessListener { continuation.resume(it) }
              .addOnFailureListener { continuation.resumeWithException(it) }
              .addOnCanceledListener { continuation.resumeWithException(IllegalStateException("Face detection cancelled")) }
          }
        } finally { detector.close() }
      } catch (e: Exception) { throw failure("VISION_FAILED", e) }
      mapOf("width" to width, "height" to height, "faces" to faces.map { face ->
        val bounds = face.boundingBox
        mapOf("x" to bounds.left.toDouble(), "y" to bounds.top.toDouble(),
          "width" to bounds.width().toDouble(), "height" to bounds.height().toDouble())
      })
    } finally { bitmap.recycle() }
  }

  private fun loadImage(imageUri: String): Bitmap {
    val uri = Uri.parse(imageUri)
    fun openStream(): InputStream? = when (uri.scheme) {
      "file", null -> File(uri.path ?: imageUri).inputStream()
      "content" -> context.contentResolver.openInputStream(uri)
      else -> throw IllegalArgumentException("Face checks require a local image")
    }
    val decoded = openStream()?.use { BitmapFactory.decodeStream(it) }
      ?: throw IllegalArgumentException("Could not decode the face-check image")
    try {
      val orientation = openStream()?.use { ExifInterface(it).getAttributeInt(
        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
      ) } ?: ExifInterface.ORIENTATION_NORMAL
      val matrix = Matrix()
      when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return decoded
      }
      val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
      if (upright !== decoded) decoded.recycle()
      return upright
    } catch (e: Exception) { decoded.recycle(); throw e }
  }
}
