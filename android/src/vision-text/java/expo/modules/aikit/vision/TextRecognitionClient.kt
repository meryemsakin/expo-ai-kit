package expo.modules.aikit.vision

import android.graphics.Point
import android.graphics.Rect
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class TextRecognitionClient(support: VisionSupport) : VisionFeatureClient(support), TextFeature {
  companion object {
    private const val TEXT_MAX_EDGE = 2048
    private const val TEXT_MAX_PIXELS = 4_000_000
  }

  private enum class Script { LATIN, CHINESE, JAPANESE, KOREAN, DEVANAGARI }

  private val play = PlayModules(support)
  private fun requirePlayServices(feature: String) = play.requirePlayServices(feature)
  private suspend fun requireModules(feature: String, apis: List<OptionalModuleApi>) = play.requireModules(feature, apis)

  private val textClientsLock = Any()
  private val textClients = HashMap<Script, TextRecognizer>()

  private fun textClient(script: Script): TextRecognizer =
    synchronized(textClientsLock) {
      textClients[script] ?: when (script) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        Script.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        Script.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
      }.also { textClients[script] = it }
    }

  /**
   * Map normalized BCP-47 tags to script models, preserving order. Empty →
   * Latin. The CJK and Devanagari models also read Latin text, so Latin is
   * dropped whenever another script is requested (no second pass).
   */
  private fun scriptsFor(languages: List<String>): List<Script> {
    if (languages.isEmpty()) return listOf(Script.LATIN)
    val scripts = LinkedHashSet<Script>()
    for (tag in languages) {
      val lower = tag.lowercase()
      val parts = lower.split('-')
      val primary = parts.firstOrNull().orEmpty()
      scripts.add(
        when {
          primary == "zh" || primary == "yue" || parts.contains("hans") || parts.contains("hant") -> Script.CHINESE
          primary == "ja" || parts.contains("jpan") -> Script.JAPANESE
          primary == "ko" || parts.contains("kore") -> Script.KOREAN
          primary == "hi" || primary == "mr" || primary == "ne" || primary == "sa" || parts.contains("deva") -> Script.DEVANAGARI
          else -> Script.LATIN
        }
      )
    }
    val nonLatin = scripts.filter { it != Script.LATIN }
    return if (nonLatin.isNotEmpty()) nonLatin else listOf(Script.LATIN)
  }

  override suspend fun availability(): Map<String, Any?> = withContext(Dispatchers.IO) {
    if (!play.playServicesAvailable()) {
      return@withContext mapOf("status" to "unavailable", "reason" to "device")
    }
    val status = try {
      if (play.modulesInstalled(listOf(textClient(Script.LATIN)))) "available" else "downloadable"
    } catch (_: Throwable) {
      "downloadable"
    }
    mapOf("status" to status)
  }

  override suspend fun prepare(languages: List<String>, onProgress: (Double) -> Unit) = withContext(Dispatchers.IO) {
    requirePlayServices("Text recognition")
    val apis: List<OptionalModuleApi> = scriptsFor(languages).map { textClient(it) }
    wrapping("DOWNLOAD_FAILED") { play.installModules(apis, onProgress) }
  }

  // ==================================================================
  // Text recognition (OCR)
  // ==================================================================

  override suspend fun recognizeText(
    uri: String,
    languages: List<String>,
    minTextHeight: Double
  ): Map<String, Any?> = withContext(Dispatchers.Default) {
    requirePlayServices("Text recognition")
    val scripts = scriptsFor(languages)
    val clients = scripts.map { textClient(it) }
    requireModules("text recognition", clients)

    val source = loadBitmap(uri, TEXT_MAX_PIXELS)
    try {
      val input = mlKitInput(source, TEXT_MAX_EDGE)
      try {
        val image = InputImage.fromBitmap(input, 0)
        val outputs = wrapping("VISION_FAILED") {
          coroutineScope {
            clients.map { client ->
              async { mapText(client.process(image).await(), input.width, input.height, minTextHeight) }
            }.awaitAll()
          }
        }
        merge(outputs)
      } finally {
        if (input !== source) input.recycle()
      }
    } finally {
      source.recycle()
    }
  }

  private class Block(
    val text: String,
    val bounds: Map<String, Double>,
    val lines: List<Map<String, Any?>>,
    val language: String?,
    val cornerPoints: List<Map<String, Double>>?
  ) {
    fun toMap(): Map<String, Any?> = buildMap {
      put("text", text)
      put("bounds", bounds)
      put("lines", lines)
      if (language != null) put("language", language)
      if (cornerPoints != null) put("cornerPoints", cornerPoints)
    }
  }

  private fun mapText(visionText: Text, width: Int, height: Int, minTextHeight: Double): List<Block> {
    val blocks = ArrayList<Block>()
    for (block in visionText.textBlocks) {
      val lines = ArrayList<Map<String, Any?>>()
      for (line in block.lines) {
        val bounds = normalizeRect(line.boundingBox, width, height) ?: continue
        if (minTextHeight > 0 && (bounds["height"] ?: 0.0) < minTextHeight) continue
        lines.add(
          buildMap {
            put("text", line.text)
            put("bounds", bounds)
            val confidence = line.confidence
            if (confidence > 0f) put("confidence", confidence.toDouble())
            language(line.recognizedLanguage)?.let { put("language", it) }
            normalizeCorners(line.cornerPoints, width, height)?.let { put("cornerPoints", it) }
          }
        )
      }
      if (lines.isEmpty()) continue
      val blockBounds = normalizeRect(block.boundingBox, width, height)
        ?: unionBounds(lines.map { @Suppress("UNCHECKED_CAST") (it["bounds"] as Map<String, Double>) })
      blocks.add(
        Block(
          text = block.text,
          bounds = blockBounds,
          lines = lines,
          language = language(block.recognizedLanguage),
          cornerPoints = normalizeCorners(block.cornerPoints, width, height)
        )
      )
    }
    return blocks
  }

  /** Merge multi-script passes: drop near-duplicates (IoU ≥ 0.5, same text), sort in reading order. */
  private fun merge(outputs: List<List<Block>>): Map<String, Any?> {
    val merged = ArrayList<Block>()
    for (output in outputs) {
      for (block in output) {
        val duplicate = merged.any { existing ->
          iou(existing.bounds, block.bounds) >= 0.5 &&
            existing.text.filterNot { it.isWhitespace() } == block.text.filterNot { it.isWhitespace() }
        }
        if (!duplicate) merged.add(block)
      }
    }
    if (outputs.size > 1) {
      merged.sortWith(compareBy({ it.bounds["y"] ?: 0.0 }, { it.bounds["x"] ?: 0.0 }))
    }
    return mapOf(
      "text" to merged.joinToString("\n") { it.text },
      "blocks" to merged.map { it.toMap() }
    )
  }

  private fun language(tag: String?): String? =
    if (tag.isNullOrEmpty() || tag == "und") null else tag

  private fun normalizeRect(box: Rect?, width: Int, height: Int): Map<String, Double>? {
    if (box == null || width <= 0 || height <= 0) return null
    return mapOf(
      "x" to (box.left.toDouble() / width).coerceIn(0.0, 1.0),
      "y" to (box.top.toDouble() / height).coerceIn(0.0, 1.0),
      "width" to (box.width().toDouble() / width).coerceIn(0.0, 1.0),
      "height" to (box.height().toDouble() / height).coerceIn(0.0, 1.0)
    )
  }

  private fun normalizeCorners(points: Array<Point>?, width: Int, height: Int): List<Map<String, Double>>? {
    if (points == null || points.isEmpty() || width <= 0 || height <= 0) return null
    return points.map {
      mapOf(
        "x" to (it.x.toDouble() / width).coerceIn(0.0, 1.0),
        "y" to (it.y.toDouble() / height).coerceIn(0.0, 1.0)
      )
    }
  }

  private fun unionBounds(rects: List<Map<String, Double>>): Map<String, Double> {
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (r in rects) {
      val x = r["x"] ?: 0.0
      val y = r["y"] ?: 0.0
      minX = minOf(minX, x)
      minY = minOf(minY, y)
      maxX = maxOf(maxX, x + (r["width"] ?: 0.0))
      maxY = maxOf(maxY, y + (r["height"] ?: 0.0))
    }
    if (rects.isEmpty()) return mapOf("x" to 0.0, "y" to 0.0, "width" to 0.0, "height" to 0.0)
    return mapOf("x" to minX, "y" to minY, "width" to (maxX - minX).coerceAtLeast(0.0), "height" to (maxY - minY).coerceAtLeast(0.0))
  }

  private fun iou(a: Map<String, Double>, b: Map<String, Double>): Double {
    val ax = a["x"] ?: 0.0
    val ay = a["y"] ?: 0.0
    val aw = a["width"] ?: 0.0
    val ah = a["height"] ?: 0.0
    val bx = b["x"] ?: 0.0
    val by = b["y"] ?: 0.0
    val bw = b["width"] ?: 0.0
    val bh = b["height"] ?: 0.0
    val iw = (minOf(ax + aw, bx + bw) - maxOf(ax, bx)).coerceAtLeast(0.0)
    val ih = (minOf(ay + ah, by + bh) - maxOf(ay, by)).coerceAtLeast(0.0)
    val intersection = iw * ih
    if (intersection <= 0.0) return 0.0
    val union = aw * ah + bw * bh - intersection
    return if (union > 0.0) intersection / union else 0.0
  }

}
