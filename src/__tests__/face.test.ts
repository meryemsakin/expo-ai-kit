import { classifyFaces, resolveCheckFaceOptions, validateFaceImage } from '../face';

const largest = { x: 40, y: 80, width: 100, height: 100 };
const options = resolveCheckFaceOptions();
const classify = (faces: (typeof largest)[], areaThreshold = 0.2) =>
  classifyFaces({ width: 1000, height: 800, faces }, { ...options, areaThreshold });

it('preserves the original defaults and validates thresholds', () => {
  expect(options).toEqual({ minPixelSize: 500_000, areaThreshold: 0.2 });
  for (const minPixelSize of [-1, NaN, Infinity, '500000']) {
    expect(() => resolveCheckFaceOptions({ minPixelSize: minPixelSize as number })).toThrow();
  }
  for (const areaThreshold of [-0.1, 1.1, NaN, Infinity, '0.2']) {
    expect(() => resolveCheckFaceOptions({ areaThreshold: areaThreshold as number })).toThrow();
  }
  expect(resolveCheckFaceOptions({ minPixelSize: 0, areaThreshold: 0 })).toEqual({
    minPixelSize: 0,
    areaThreshold: 0,
  });
});

it('checks total image resolution before faces, with an inclusive pixel floor', () => {
  expect(classifyFaces({ width: 500, height: 999, faces: [largest] }, options)).toEqual({
    status: 'LOW_QUALITY',
    faceCount: 0,
  });
  expect(classifyFaces({ width: 500, height: 1000, faces: [largest] }, options).status).toBe(
    'READY'
  );
});

it('ignores small background faces and returns unmodified upright pixel bounds', () => {
  expect(classify([{ x: 0, y: 0, width: 10, height: 10 }, largest])).toEqual({
    status: 'READY',
    faceCount: 1,
    dominantFaceBounds: largest,
  });
});

it('counts only faces strictly above the area threshold', () => {
  expect(classify([largest, { ...largest, width: 20 }]).status).toBe('READY');
  expect(classify([largest, { ...largest, width: 21 }, { ...largest, width: 1 }])).toEqual({
    status: 'MULTIPLE_FACES',
    faceCount: 2,
  });
  expect(classify([largest], 1)).toEqual({ status: 'NO_FACE', faceCount: 0 });
  expect(classify([largest, { ...largest, width: 1 }], 0).faceCount).toBe(2);
});

it('handles no faces and zero-area boxes without inventing a dominant face', () => {
  expect(classify([])).toEqual({ status: 'NO_FACE', faceCount: 0 });
  expect(classify([{ ...largest, width: 0 }])).toEqual({ status: 'NO_FACE', faceCount: 0 });
});

it('accepts local image sources and never fetches remote images', () => {
  expect(validateFaceImage(' /tmp/a.jpg ', 'ios')).toBe('/tmp/a.jpg');
  expect(validateFaceImage('file:///tmp/a.jpg', 'ios')).toBe('file:///tmp/a.jpg');
  expect(validateFaceImage('content://photos/1', 'android')).toBe('content://photos/1');
  for (const uri of [
    '',
    'https://example.com/a.jpg',
    'data:image/png;base64,abc',
    'content://photos/1',
  ]) {
    expect(() => validateFaceImage(uri, 'ios')).toThrow();
  }
});
