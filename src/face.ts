import type { DetectedFace, FaceDetectionResult } from './types';

/** Native payload: upright pixel dimensions and raw face boxes in upright pixels. */
export interface NativeFaceDetection {
  width: number;
  height: number;
  faces: { x: number; y: number; width: number; height: number; confidence?: number }[];
}

/** Local images only: detection never fetches, so remote URLs are rejected before native. */
export function validateFaceImageUri(uri: unknown, platform: string): string {
  const trimmed = typeof uri === 'string' ? uri.trim() : '';
  if (
    !trimmed ||
    !(
      trimmed.startsWith('/') ||
      trimmed.startsWith('file://') ||
      (platform === 'android' && trimmed.startsWith('content://'))
    )
  ) {
    throw new Error(
      'detectFaces(): uri must be a local file URI or absolute path (content:// is also supported on Android)'
    );
  }
  return trimmed;
}

const isFinitePositive = (n: unknown): n is number =>
  typeof n === 'number' && Number.isFinite(n) && n > 0;
const isFiniteNumber = (n: unknown): n is number => typeof n === 'number' && Number.isFinite(n);

/**
 * Shape the native payload into the public result: every box is intersected
 * with the image (detectors can report boxes past the edges), expressed in
 * both normalized and pixel coordinates, and sorted largest first. Boxes that
 * are empty after clamping are dropped. Throws on a malformed payload.
 */
export function normalizeFaceDetection(raw: unknown): FaceDetectionResult {
  const r = raw as NativeFaceDetection | null;
  if (!r || !isFinitePositive(r.width) || !isFinitePositive(r.height) || !Array.isArray(r.faces)) {
    throw new Error('detectFaces(): malformed native result');
  }
  const { width, height } = r;
  const faces: DetectedFace[] = [];
  for (const f of r.faces) {
    if (!f || ![f.x, f.y, f.width, f.height].every(isFiniteNumber)) {
      throw new Error('detectFaces(): malformed native face');
    }
    const left = Math.max(0, Math.min(f.x, f.x + f.width));
    const top = Math.max(0, Math.min(f.y, f.y + f.height));
    const right = Math.min(width, Math.max(f.x, f.x + f.width));
    const bottom = Math.min(height, Math.max(f.y, f.y + f.height));
    if (right <= left || bottom <= top) continue;
    const pixelBounds = { x: left, y: top, width: right - left, height: bottom - top };
    const face: DetectedFace = {
      bounds: {
        x: left / width,
        y: top / height,
        width: pixelBounds.width / width,
        height: pixelBounds.height / height,
      },
      pixelBounds,
    };
    if (isFiniteNumber(f.confidence)) face.confidence = Math.min(1, Math.max(0, f.confidence));
    faces.push(face);
  }
  faces.sort(
    (a, b) =>
      b.pixelBounds.width * b.pixelBounds.height - a.pixelBounds.width * a.pixelBounds.height
  );
  return { width, height, faces };
}
