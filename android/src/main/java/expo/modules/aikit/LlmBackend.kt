package expo.modules.aikit

/** Emits module events (onStreamToken, onDownloadProgress, onModelStateChange) for the LLM backend. */
fun interface LlmEventSink {
  fun send(name: String, payload: Map<String, Any?>)
}

/**
 * Text generation: the built-in ML Kit Prompt API plus downloadable LiteRT-LM
 * models. Implemented by `expo.modules.aikit.llm.AndroidLlmBackend`, which is
 * compiled only when the app enables `["expo-ai-kit", { "llm": true }]` (the
 * config plugin writes the `expoAiKit.llm` gradle property; build.gradle then
 * adds the ML Kit GenAI and LiteRT-LM dependencies and the src/llm source set).
 * ExpoAiKitModule resolves it by reflection; when absent, isAvailable() is
 * false and every other generation call throws LLM_NOT_ENABLED.
 *
 * Every method throws RuntimeException with the "CODE:modelId:reason" contract.
 */
interface LlmBackend {
  /** True when the ML Kit Prompt API can serve on this device (model may still need preparing). */
  fun isBuiltInAvailable(): Boolean

  suspend fun prepareBuiltInModel()

  /** Full-history generation routed to the active model. Returns `{ text }`. */
  suspend fun sendMessage(messages: List<Map<String, Any>>, fallbackSystemPrompt: String): Map<String, Any?>

  /** Launches a detached stream that reports through onStreamToken events for [sessionId]. */
  suspend fun startStreaming(messages: List<Map<String, Any>>, fallbackSystemPrompt: String, sessionId: String)

  fun stopStreaming(sessionId: String)

  fun builtInModels(): List<Map<String, Any?>>

  /** "ready", "downloaded", or "not-downloaded". */
  fun downloadableModelStatus(modelId: String): String

  suspend fun setModel(modelId: String, minRamBytes: Long, backend: String, generation: Map<String, Double>)

  fun activeModel(): String

  suspend fun unloadModel()

  suspend fun downloadModel(modelId: String, url: String, sha256: String)

  fun cancelDownload(modelId: String)

  suspend fun deleteModel(modelId: String)
}
