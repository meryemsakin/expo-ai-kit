package expo.modules.aikit

import android.content.Context
import java.io.File

/**
 * Where downloadable LiteRT-LM models live on disk. Lives in the always-compiled
 * source set so deleteModel() can reclaim a model's storage even in a build that
 * no longer enables the `llm` config-plugin flag.
 */
internal object LlmModelFiles {
  fun modelsDirectory(context: Context): File = File(context.filesDir, "models")

  fun modelFile(context: Context, modelId: String): File =
    File(modelsDirectory(context), "$modelId.litertlm")

  /** In-progress download; removed on failure and on delete. */
  fun partialFile(context: Context, modelId: String): File =
    File(modelsDirectory(context), "$modelId.litertlm.tmp")

  fun delete(context: Context, modelId: String) {
    modelFile(context, modelId).delete()
    partialFile(context, modelId).delete()
  }
}
