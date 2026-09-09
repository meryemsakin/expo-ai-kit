import Foundation

/// Where downloadable LiteRT-LM models live on disk. Always compiled, so
/// deleteModel() can reclaim a model's storage even in a build that no longer
/// enables the `llm` config-plugin option.
enum LlmModelFiles {
  static var modelsDirectory: URL {
    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    let dir = support.appendingPathComponent("ExpoAiKit/Models", isDirectory: true)
    try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    return dir
  }

  static func modelFileURL(_ modelId: String) -> URL {
    return modelsDirectory.appendingPathComponent("\(modelId).litertlm")
  }

  /// In-progress download; removed on failure and on delete.
  static func partialFileURL(_ modelId: String) -> URL {
    return modelFileURL(modelId).appendingPathExtension("tmp")
  }

  static func delete(_ modelId: String) {
    try? FileManager.default.removeItem(at: modelFileURL(modelId))
    try? FileManager.default.removeItem(at: partialFileURL(modelId))
  }
}
