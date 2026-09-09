package expo.modules.aikit

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.functions.Coroutine
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class ExpoAiKitModule : Module() {

  // Optional text backend (ML Kit Prompt API + LiteRT-LM). Present only when
  // the app was prebuilt with ["expo-ai-kit", { "llm": true }], which compiles
  // android/src/llm and adds the genai-prompt and litertlm-android dependencies.
  // Resolved by reflection so this module compiles without them on the
  // classpath (kept by consumer-rules.pro under R8). Without it, isAvailable()
  // is false and every other generation call throws LLM_NOT_ENABLED.
  private val llmBackend: LlmBackend? by lazy {
    try {
      Class.forName("expo.modules.aikit.llm.AndroidLlmBackend")
        .getDeclaredConstructor(Context::class.java, LlmEventSink::class.java)
        .newInstance(
          appContext.reactContext ?: throw RuntimeException("React context not available"),
          LlmEventSink { name, payload -> sendEvent(name, payload) }
        )
        as LlmBackend
    } catch (e: ReflectiveOperationException) {
      null
    } catch (e: LinkageError) {
      null
    }
  }

  private fun requireLlmBackend(): LlmBackend =
    llmBackend ?: throw RuntimeException(
      "LLM_NOT_ENABLED:mlkit:" +
        "The LLM is opt-in. Add [\"expo-ai-kit\", { \"llm\": true }] to your app config plugins " +
        "and make a new native build (dev client / EAS, not OTA)."
    )

  // Embedding asset lifecycle (download/status/delete). Always available, it
  // has no MediaPipe dependency; only the inference backend below is optional.
  private val embeddingAssets by lazy {
    EmbeddingAssetManager(appContext.reactContext ?: throw RuntimeException("React context not available"))
  }

  // Optional EmbeddingGemma inference backend. Present only when the app was
  // prebuilt with ["expo-ai-kit", { "androidEmbeddings": true }], which
  // compiles android/src/embeddings and adds the MediaPipe tasks-text
  // dependency. Resolved by reflection so this module compiles without
  // MediaPipe on the classpath (kept by consumer-rules.pro under R8).
  private val embeddingBackend: EmbeddingBackend? by lazy {
    try {
      Class.forName("expo.modules.aikit.embeddings.EmbeddingGemmaBackend")
        .getDeclaredConstructor(Context::class.java)
        .newInstance(appContext.reactContext ?: throw RuntimeException("React context not available"))
        as EmbeddingBackend
    } catch (e: ReflectiveOperationException) {
      null
    } catch (e: LinkageError) {
      null
    }
  }

  // Optional ML Kit speech backend. Present only when the app was prebuilt
  // with ["expo-ai-kit", { "speech": true }], which compiles android/src/speech
  // and adds the genai-speech-recognition dependency. Resolved by reflection so
  // this module compiles without ML Kit speech on the classpath.
  private val speechBackend: SpeechBackend? by lazy {
    try {
      Class.forName("expo.modules.aikit.speech.MlKitSpeechBackend")
        .getDeclaredConstructor(Context::class.java)
        .newInstance(appContext.reactContext ?: throw RuntimeException("React context not available"))
        as SpeechBackend
    } catch (e: ReflectiveOperationException) {
      null
    } catch (e: LinkageError) {
      null
    }
  }

  // Optional ML Kit vision backend. Present only when the app was prebuilt
  // with ["expo-ai-kit", { "vision": true }], which compiles android/src/vision
  // and adds the ML Kit vision dependencies. Resolved by reflection so this
  // module compiles without ML Kit vision on the classpath.
  private val visionBackend: VisionBackend? by lazy {
    try {
      Class.forName("expo.modules.aikit.vision.MlKitVisionBackend")
        .getDeclaredConstructor(Context::class.java)
        .newInstance(appContext.reactContext ?: throw RuntimeException("React context not available"))
        as VisionBackend
    } catch (e: ReflectiveOperationException) {
      null
    } catch (e: LinkageError) {
      null
    }
  }

  private fun requireVisionBackend(): VisionBackend =
    visionBackend ?: throw RuntimeException(
      "VISION_NOT_ENABLED:mlkit-vision:" +
        "Vision is opt-in. Add [\"expo-ai-kit\", { \"vision\": true }] to your app config plugins " +
        "and make a new native build (dev client / EAS, not OTA)."
    )

  private fun requireSpeechBackend(): SpeechBackend =
    speechBackend ?: throw RuntimeException(
      "SPEECH_NOT_ENABLED:mlkit-speech:" +
        "Speech is opt-in. Add [\"expo-ai-kit\", { \"speech\": true }] to your app config plugins " +
        "and make a new native build (dev client / EAS, not OTA)."
    )

  // SupervisorJob is load-bearing: with a plain Job, one failed transcription
  // would cancel the scope forever and every later call would surface as
  // INFERENCE_CANCELLED instead of its real typed error.
  private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val activeSpeechJobs = mutableMapOf<String, Deferred<Map<String, Any?>>>()
  private var activeLiveSpeechSessionId: String? = null

  private fun requireEmbeddingBackend(): EmbeddingBackend =
    embeddingBackend ?: throw RuntimeException(
      "EMBEDDINGS_NOT_ENABLED:${EmbeddingAssetManager.EMBEDDING_MODEL_ID}:" +
        "Android embeddings are opt-in. Add [\"expo-ai-kit\", { \"androidEmbeddings\": true }] to your " +
        "app config plugins and make a new native build (dev client / EAS, not OTA). " +
        "Enabling adds ~25 MB to the APK; the ~184 MB EmbeddingGemma model then downloads via prepareEmbeddingModel()."
    )

  // Android's Expo event bridge retains each event until JS consumes it. The
  // download loop reads 8KB chunks, so emitting one event per chunk overflows
  // ART's global JNI reference table on large model downloads.
  private var lastDownloadProgressAt = 0L
  private var lastDownloadProgress = -1.0

  override fun definition() = ModuleDefinition {
    Name("ExpoAiKit")

    Events("onStreamToken", "onDownloadProgress", "onModelStateChange", "onTranscriptionUpdate")

    // ==================================================================
    // Text generation (ML Kit Prompt API + LiteRT-LM, opt-in)
    // ==================================================================
    // Compiled only with ["expo-ai-kit", { "llm": true }]; without the flag
    // the backend is null: isAvailable() reports false, getBuiltInModels()
    // reports the built-in as unavailable, and the generation, activation, and
    // download calls throw LLM_NOT_ENABLED. stop/cancel/unload stay lenient
    // no-ops, and deleteModel() still reclaims a model downloaded by an earlier
    // build that had the flag on.

    Function("isAvailable") {
      llmBackend?.isBuiltInAvailable() ?: false
    }

    AsyncFunction("prepareBuiltInModel") Coroutine { ->
      requireLlmBackend().prepareBuiltInModel()
    }

    // sessionId is accepted for API parity with iOS. Non-streaming generation on
    // Android isn't separately cancellable (best-effort), so the id is unused here.
    AsyncFunction("sendMessage") Coroutine { messages: List<Map<String, Any>>, fallbackSystemPrompt: String, sessionId: String ->
      requireLlmBackend().sendMessage(messages, fallbackSystemPrompt)
    }

    AsyncFunction("startStreaming") Coroutine { messages: List<Map<String, Any>>, fallbackSystemPrompt: String, sessionId: String ->
      requireLlmBackend().startStreaming(messages, fallbackSystemPrompt, sessionId)
      // Last expression must be Unit: Coroutine { } infers the JS return value
      // from it, and a non-convertible value would reject every promise.
      Unit
    }

    AsyncFunction("stopStreaming") { sessionId: String ->
      llmBackend?.stopStreaming(sessionId)
      // Last expression must be Unit, see startStreaming.
      Unit
    }

    // ==================================================================
    // Embeddings (EmbeddingGemma via MediaPipe TextEmbedder, opt-in)
    // ==================================================================
    // The inference backend is compiled in only when the app enables the
    // config-plugin flag ["expo-ai-kit", { "androidEmbeddings": true }];
    // otherwise the functions below throw EMBEDDINGS_NOT_ENABLED (except
    // cancel/delete, which stay lenient so an app that later disables the flag
    // can still reclaim the ~184 MB asset). embed() NEVER triggers a download,
    // the asset is managed exclusively by prepare/cancel/delete. Deliberately
    // outside the generation path: not routed through setModel(), not guarded
    // by INFERENCE_BUSY (the backend serializes internally instead).

    AsyncFunction("embed") Coroutine { texts: List<String>, task: String, language: String ->
      // `language` is accepted and ignored: EmbeddingGemma is natively
      // multilingual with a single vector space, there is nothing to select.
      val backend = requireEmbeddingBackend()
      if (!embeddingAssets.isDownloaded()) {
        throw RuntimeException(
          "MODEL_NOT_DOWNLOADED:${EmbeddingAssetManager.EMBEDDING_MODEL_ID}:" +
            "The EmbeddingGemma model (~184 MB) is not on this device. " +
            "Call prepareEmbeddingModel() first, embed() never downloads."
        )
      }
      val embeddings = backend.embed(embeddingAssets.modelFile().absolutePath, texts, task)
      // Identity (id/revision) is attached by the JS layer from its pinned
      // artifacts; native reports the raw vectors and dimensionality.
      mapOf(
        "embeddings" to embeddings,
        "dimensions" to EmbeddingAssetManager.EMBEDDING_DIMENSIONS
      )
    }

    Function("getEmbeddingModelStatus") { language: String ->
      requireEmbeddingBackend()
      embeddingAssets.status()
    }

    AsyncFunction("prepareEmbeddingModel") Coroutine { url: String, sha256: String, language: String ->
      requireEmbeddingBackend()
      if (embeddingAssets.isDownloaded()) return@Coroutine
      lastDownloadProgressAt = 0L
      lastDownloadProgress = -1.0
      sendEvent("onModelStateChange", mapOf(
        "modelId" to EmbeddingAssetManager.EMBEDDING_MODEL_ID,
        "status" to "downloading"
      ))
      try {
        embeddingAssets.download(url, sha256) { bytesRead, totalBytes ->
          val progress = if (totalBytes > 0) bytesRead.toDouble() / totalBytes else 0.0
          val now = SystemClock.elapsedRealtime()
          if (
            progress >= 1.0 ||
            now - lastDownloadProgressAt >= 250L ||
            progress - lastDownloadProgress >= 0.01
          ) {
            lastDownloadProgressAt = now
            lastDownloadProgress = progress
            sendEvent("onDownloadProgress", mapOf(
              "modelId" to EmbeddingAssetManager.EMBEDDING_MODEL_ID,
              "progress" to progress
            ))
          }
        }
        sendEvent("onModelStateChange", mapOf(
          "modelId" to EmbeddingAssetManager.EMBEDDING_MODEL_ID,
          "status" to "downloaded"
        ))
      } catch (e: Exception) {
        // Failed or cancelled downloads clean their partial file; report what's
        // actually on disk (a prior verified copy may remain).
        sendEvent("onModelStateChange", mapOf(
          "modelId" to EmbeddingAssetManager.EMBEDDING_MODEL_ID,
          "status" to embeddingAssets.status()
        ))
        throw e
      }
    }

    AsyncFunction("cancelEmbeddingModelDownload") {
      embeddingAssets.cancelDownload()
    }

    AsyncFunction("deleteEmbeddingModel") Coroutine { ->
      embeddingBackend?.close()
      embeddingAssets.delete()
      sendEvent("onModelStateChange", mapOf(
        "modelId" to EmbeddingAssetManager.EMBEDDING_MODEL_ID,
        "status" to "not-downloaded"
      ))
    }

    // Only meaningful on iOS (NLContextualEmbedding catalog); the JS layer
    // answers [] on Android without calling native. Registered for parity.
    Function("getSupportedEmbeddingLanguages") {
      listOf<String>()
    }

    // ==================================================================
    // Model discovery
    // ==================================================================

    Function("getBuiltInModels") {
      llmBackend?.builtInModels() ?: listOf(
        mapOf(
          "id" to "mlkit",
          "name" to "ML Kit Prompt API",
          "available" to false,
          "platform" to "android",
          "contextWindow" to 4096
        )
      )
    }

    Function("getDownloadableModelStatus") { modelId: String ->
      requireLlmBackend().downloadableModelStatus(modelId)
    }

    Function("getDeviceRamBytes") {
      val activityManager = appContext.reactContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      if (activityManager != null) {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        memInfo.totalMem
      } else {
        0L
      }
    }

    // ==================================================================
    // Model selection & memory management
    // ==================================================================

    AsyncFunction("setModel") Coroutine { modelId: String, minRamBytes: Long, backend: String, generation: Map<String, Double> ->
      requireLlmBackend().setModel(modelId, minRamBytes, backend, generation)
    }

    Function("getActiveModel") {
      llmBackend?.activeModel() ?: "mlkit"
    }

    AsyncFunction("unloadModel") Coroutine { ->
      llmBackend?.unloadModel()
    }

    // ==================================================================
    // Model lifecycle (downloadable models only)
    // ==================================================================

    AsyncFunction("downloadModel") Coroutine { modelId: String, url: String, sha256: String ->
      requireLlmBackend().downloadModel(modelId, url, sha256)
    }

    AsyncFunction("cancelDownload") Coroutine { modelId: String ->
      llmBackend?.cancelDownload(modelId)
    }

    AsyncFunction("deleteModel") Coroutine { modelId: String ->
      val backend = llmBackend
      if (backend != null) {
        backend.deleteModel(modelId)
      } else {
        // Reclaim storage from a build that had the flag on.
        LlmModelFiles.delete(
          appContext.reactContext ?: throw RuntimeException("React context not available"), modelId
        )
        sendEvent("onModelStateChange", mapOf("modelId" to modelId, "status" to "not-downloaded"))
      }
    }

    // ==================================================================
    // Speech-to-text (ML Kit GenAI Speech Recognition, opt-in)
    // ==================================================================
    // Compiled only with ["expo-ai-kit", { "speech": true }]; without the flag
    // the backend is null and calls throw SPEECH_NOT_ENABLED. Independent of
    // the generation single-flight; the JS layer enforces the speech one.

    AsyncFunction("getSpeechAvailability") Coroutine { locale: String ->
      // Availability never throws for the flag: it *reports* not-enabled.
      val backend = speechBackend
        ?: return@Coroutine mapOf("status" to "unavailable", "reason" to "not-enabled")
      backend.availability(locale)
    }

    AsyncFunction("prepareSpeechRecognition") Coroutine { locale: String ->
      requireSpeechBackend().prepare(locale) { progress ->
        sendEvent("onDownloadProgress", mapOf(
          "modelId" to "mlkit-speech",
          "progress" to progress
        ))
      }
    }

    AsyncFunction("getSupportedSpeechLocalesNative") Coroutine { ->
      // The engine has no locale-enumeration API; the JS registry answers.
      emptyList<String>()
    }

    AsyncFunction("getSpeechPermissions") { promise: Promise ->
      Permissions.getPermissionsWithPermissionsManager(
        appContext.permissions, promise, Manifest.permission.RECORD_AUDIO
      )
    }

    AsyncFunction("requestSpeechPermissions") { promise: Promise ->
      Permissions.askForPermissionsWithPermissionsManager(
        appContext.permissions, promise, Manifest.permission.RECORD_AUDIO
      )
    }

    AsyncFunction("transcribeAudio") Coroutine {
      uri: String, base64: String, mediaType: String, locale: String, sessionId: String ->
      val unused = mediaType // Android decodes by content (MediaExtractor sniffs)
      val backend = requireSpeechBackend()
      // file:// URIs can carry percent-escapes (expo-audio recordings do);
      // Uri.parse().path decodes them, a raw prefix-strip would not.
      val path = when {
        uri.isEmpty() -> null
        uri.startsWith("file://") -> android.net.Uri.parse(uri).path ?: uri.removePrefix("file://")
        else -> uri
      }
      val payload = if (base64.isEmpty()) null else base64
      val work = speechScope.async { backend.transcribeFile(path, payload, locale) }
      activeSpeechJobs[sessionId] = work
      try {
        work.await()
      } catch (e: CancellationException) {
        throw RuntimeException("INFERENCE_CANCELLED:mlkit-speech:Transcription was cancelled")
      } finally {
        activeSpeechJobs.remove(sessionId)
      }
    }

    AsyncFunction("startTranscription") Coroutine { locale: String, sessionId: String ->
      activeLiveSpeechSessionId = sessionId
      requireSpeechBackend().startLive(
        locale,
        onUpdate = { text, isFinal ->
          sendEvent("onTranscriptionUpdate", mapOf(
            "sessionId" to sessionId,
            "text" to text,
            "isFinal" to isFinal
          ))
        },
        onError = { contract ->
          sendEvent("onTranscriptionUpdate", mapOf(
            "sessionId" to sessionId,
            "text" to "",
            "isFinal" to true,
            "error" to contract
          ))
        },
        onEnd = {
          sendEvent("onTranscriptionUpdate", mapOf(
            "sessionId" to sessionId,
            "text" to "",
            "isFinal" to false,
            "isSessionEnd" to true
          ))
        }
      )
    }

    AsyncFunction("stopTranscription") Coroutine { sessionId: String ->
      // Scope teardown to the session that was asked for: cancelling an
      // aborted batch transcription must not kill an unrelated live session.
      val batch = activeSpeechJobs.remove(sessionId)
      if (batch != null) {
        batch.cancel()
      } else if (sessionId == activeLiveSpeechSessionId) {
        activeLiveSpeechSessionId = null
        speechBackend?.stopLive()
      }
      // Last expression must be Unit, see startStreaming.
      Unit
    }

    // ==================================================================
    // Vision (ML Kit, opt-in)
    // ==================================================================
    // Compiled only with ["expo-ai-kit", { "vision": true }]; without the flag
    // the backend is null, availability reports 'not-enabled', and the other
    // calls throw VISION_NOT_ENABLED. Independent of the generation and speech
    // guards. prepareVision() is the only call that downloads (Google Play
    // services modules); the analysis calls throw MODEL_NOT_DOWNLOADED instead.

    AsyncFunction("getVisionAvailability") Coroutine { ->
      val backend = visionBackend
      if (backend == null) {
        val notEnabled = mapOf("status" to "unavailable", "reason" to "not-enabled")
        return@Coroutine mapOf(
          "backgroundRemoval" to notEnabled,
          "imageLabeling" to notEnabled,
          "textRecognition" to notEnabled,
          "faceDetection" to notEnabled
        )
      }
      backend.availability()
    }

    AsyncFunction("prepareVision") Coroutine { features: List<String>, languages: List<String> ->
      requireVisionBackend().prepare(features, languages) { progress ->
        sendEvent("onDownloadProgress", mapOf(
          "modelId" to "mlkit-vision",
          "progress" to progress
        ))
      }
    }

    AsyncFunction("getSupportedTextRecognitionLanguagesNative") Coroutine { ->
      // ML Kit has no enumeration API; the JS registry answers on Android.
      emptyList<String>()
    }

    AsyncFunction("detectFaces") Coroutine { uri: String ->
      requireVisionBackend().detectFaces(uri)
    }

    AsyncFunction("removeBackground") Coroutine {
      uri: String, trim: Boolean, format: String, quality: Double, maxPixels: Int,
      subjectX: Double, subjectY: Double, includeMask: Boolean ->
      requireVisionBackend().removeBackground(
        uri, trim, format, quality, maxPixels, subjectX, subjectY, includeMask
      )
    }

    AsyncFunction("labelImage") Coroutine { uri: String, maxResults: Int, minConfidence: Double ->
      requireVisionBackend().labelImage(uri, maxResults, minConfidence)
    }

    AsyncFunction("recognizeText") Coroutine {
      uri: String, languages: List<String>, recognitionLevel: String, usesLanguageCorrection: Boolean,
      customWords: List<String>, minTextHeight: Double ->
      // recognitionLevel, usesLanguageCorrection, and customWords are Vision
      // (iOS) knobs; ML Kit runs a single model with no equivalents.
      val unusedLevel = recognitionLevel
      val unusedCorrection = usesLanguageCorrection
      val unusedWords = customWords
      requireVisionBackend().recognizeText(uri, languages, minTextHeight)
    }
  }
}
