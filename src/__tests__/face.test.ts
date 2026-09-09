import { normalizeFaceDetection, validateFaceImageUri } from '../face';

const image = { width: 1000, height: 800 };

it('normalizes boxes to both coordinate spaces and sorts largest first', () => {
  const result = normalizeFaceDetection({
    ...image,
    faces: [
      { x: 0, y: 0, width: 10, height: 10 },
      { x: 100, y: 200, width: 400, height: 400 },
    ],
  });
  expect(result).toEqual({
    width: 1000,
    height: 800,
    faces: [
      {
        bounds: { x: 0.1, y: 0.25, width: 0.4, height: 0.5 },
        pixelBounds: { x: 100, y: 200, width: 400, height: 400 },
      },
      {
        bounds: { x: 0, y: 0, width: 0.01, height: 0.0125 },
        pixelBounds: { x: 0, y: 0, width: 10, height: 10 },
      },
    ],
  });
});

it('clamps boxes that run past the image and drops empty ones', () => {
  const result = normalizeFaceDetection({
    ...image,
    faces: [
      { x: 900, y: -50, width: 300, height: 200 },
      { x: 500, y: 400, width: 0, height: 40 },
      { x: 1200, y: 100, width: 50, height: 50 },
    ],
  });
  expect(result.faces).toEqual([
    {
      bounds: { x: 0.9, y: 0, width: 0.1, height: 150 / 800 },
      pixelBounds: { x: 900, y: 0, width: 100, height: 150 },
    },
  ]);
});

it('passes a finite confidence through, clamped to 0–1, and omits it otherwise', () => {
  const faces = normalizeFaceDetection({
    ...image,
    faces: [
      { x: 0, y: 0, width: 10, height: 10, confidence: 0.87 },
      { x: 0, y: 0, width: 10, height: 10, confidence: 1.2 },
      { x: 0, y: 0, width: 10, height: 10 },
    ],
  }).faces;
  expect(faces.map((f) => f.confidence)).toEqual([0.87, 1, undefined]);
  expect('confidence' in faces[2]).toBe(false);
});

it('returns an empty list when nothing is found and rejects malformed payloads', () => {
  expect(normalizeFaceDetection({ ...image, faces: [] })).toEqual({ ...image, faces: [] });
  for (const raw of [
    null,
    {},
    { width: 0, height: 800, faces: [] },
    { width: 1000, height: NaN, faces: [] },
    { ...image, faces: [{ x: 1 }] },
    { ...image, faces: [{ x: 1, y: 2, width: 'w', height: 4 }] },
  ]) {
    expect(() => normalizeFaceDetection(raw)).toThrow();
  }
});

it('accepts local image sources and never fetches remote images', () => {
  expect(validateFaceImageUri(' /tmp/a.jpg ', 'ios')).toBe('/tmp/a.jpg');
  expect(validateFaceImageUri('file:///tmp/a.jpg', 'ios')).toBe('file:///tmp/a.jpg');
  expect(validateFaceImageUri('content://photos/1', 'android')).toBe('content://photos/1');
  for (const uri of [
    '',
    undefined,
    'https://example.com/a.jpg',
    'data:image/png;base64,abc',
    'content://photos/1',
  ]) {
    expect(() => validateFaceImageUri(uri, 'ios')).toThrow();
  }
});
