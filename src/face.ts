import type { CheckFaceOptions, FaceBounds, FaceCheckResult } from './types';

/** Internal native payload. Detection stays native; both platforms share the decision rules. */
export interface FaceDetectionResult {
  width: number;
  height: number;
  faces: FaceBounds[];
}

export function resolveCheckFaceOptions(options?: CheckFaceOptions): Required<CheckFaceOptions> {
  const minPixelSize = options?.minPixelSize ?? 500_000;
  const areaThreshold = options?.areaThreshold ?? 0.2;
  if (typeof minPixelSize !== 'number' || !Number.isFinite(minPixelSize) || minPixelSize < 0) {
    throw new Error('checkFace(): minPixelSize must be a non-negative finite number');
  }
  if (
    typeof areaThreshold !== 'number' ||
    !Number.isFinite(areaThreshold) ||
    areaThreshold < 0 ||
    areaThreshold > 1
  ) {
    throw new Error('checkFace(): areaThreshold must be within [0, 1]');
  }
  return { minPixelSize, areaThreshold };
}

export function validateFaceImage(imageUri: string, platform: string): string {
  const uri = typeof imageUri === 'string' ? imageUri.trim() : '';
  if (
    !uri ||
    !(
      uri.startsWith('/') ||
      uri.startsWith('file://') ||
      (platform === 'android' && uri.startsWith('content://'))
    )
  ) {
    throw new Error(
      'checkFace(): imageUri must be a local file URI or absolute path (content:// is also supported on Android)'
    );
  }
  return uri;
}

/** Preserve expo-face-check's strict dominance threshold, including NO_FACE at threshold 1. */
export function classifyFaces(
  result: FaceDetectionResult,
  options: Required<CheckFaceOptions>
): FaceCheckResult {
  if (result.width * result.height < options.minPixelSize) {
    return { status: 'LOW_QUALITY', faceCount: 0 };
  }
  const sorted = result.faces
    .map((bounds) => ({ bounds, area: Math.abs(bounds.width * bounds.height) }))
    .sort((a, b) => b.area - a.area);
  const largest = sorted[0];
  const faceCount = largest
    ? sorted.filter((face) => face.area / largest.area > options.areaThreshold).length
    : 0;
  if (faceCount === 0) return { status: 'NO_FACE', faceCount: 0 };
  if (faceCount > 1) return { status: 'MULTIPLE_FACES', faceCount };
  return { status: 'READY', faceCount: 1, dominantFaceBounds: largest.bounds };
}
