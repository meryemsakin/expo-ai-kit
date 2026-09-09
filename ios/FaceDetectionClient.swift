import Foundation
import UIKit
import Vision

/// Full-resolution face detection on the upright image. Returns the image size
/// and raw face boxes in upright pixels; the JS layer normalizes and sorts.
enum FaceDetectionClient {
  private static func error(_ code: String, _ reason: String) -> NSError {
    NSError(domain: "ExpoAiKit", code: 0,
      userInfo: [NSLocalizedDescriptionKey: "\(code):apple-vision:\(reason)"])
  }

  static func detectFaces(uri: String) throws -> [String: Any] {
    #if targetEnvironment(simulator)
      throw error("DEVICE_NOT_SUPPORTED", "Face detection requires a physical iOS device; Vision cannot create its inference context in Simulator")
    #else
    let path: String
    if uri.hasPrefix("file://"), let url = URL(string: uri), url.isFileURL {
      path = url.path
    } else if uri.hasPrefix("/") {
      path = uri
    } else {
      throw error("IMAGE_DECODE_FAILED", "Face detection requires a local image")
    }
    return try autoreleasepool {
      guard let raw = UIImage(contentsOfFile: path) else {
        throw error("IMAGE_DECODE_FAILED", "Could not open the image")
      }
      let upright: UIImage
      if raw.imageOrientation == .up {
        upright = raw
      } else {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = raw.scale
        upright = UIGraphicsImageRenderer(size: raw.size, format: format).image { _ in
          raw.draw(in: CGRect(origin: .zero, size: raw.size))
        }
      }
      guard let image = upright.cgImage else {
        throw error("IMAGE_DECODE_FAILED", "Could not decode the image")
      }
      let width = Double(image.width)
      let height = Double(image.height)
      let request = VNDetectFaceRectanglesRequest()
      do {
        try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
      } catch {
        throw self.error("VISION_FAILED", error.localizedDescription)
      }
      // Vision's boxes are normalized with a bottom-left origin; flip to top-left pixels.
      let faces = (request.results ?? []).map { face in
        let box = face.boundingBox
        return [
          "x": Double(box.minX) * width,
          "y": Double(1 - box.minY - box.height) * height,
          "width": Double(box.width) * width,
          "height": Double(box.height) * height,
          "confidence": Double(face.confidence),
        ]
      }
      return ["width": width, "height": height, "faces": faces]
    }
    #endif
  }
}
