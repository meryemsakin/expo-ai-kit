import { Platform } from 'react-native';

import ExpoAiKitModule from '../ExpoAiKitModule';
import { checkFace, ModelError } from '../index';

jest.mock('react-native', () => ({ Platform: { OS: 'ios' } }));
jest.mock('../ExpoAiKitModule', () => ({ __esModule: true, default: { detectFaces: jest.fn() } }));
const native = ExpoAiKitModule as jest.Mocked<typeof ExpoAiKitModule>;
const platform = Platform as { OS: string };
beforeEach(() => {
  jest.resetAllMocks();
  platform.OS = 'ios';
});

it('supports Aura calls concurrently and retains its rejection statuses', async () => {
  native.detectFaces.mockResolvedValue({ width: 1000, height: 1000, faces: [] });
  const results = await Promise.all(
    Array.from({ length: 4 }, () => checkFace('file:///photo.jpg', { minPixelSize: 500_000 }))
  );
  expect(results).toEqual(Array(4).fill({ status: 'NO_FACE', faceCount: 0 }));
  expect(native.detectFaces).toHaveBeenCalledTimes(4);
  expect(native.detectFaces).toHaveBeenCalledWith('file:///photo.jpg', 500_000);
});

it('passes thresholds through the shared classifier', async () => {
  native.detectFaces.mockResolvedValue({
    width: 100,
    height: 100,
    faces: [{ x: 1, y: 2, width: 30, height: 40 }],
  });
  expect((await checkFace('/photo.jpg')).status).toBe('LOW_QUALITY');
  expect((await checkFace('/photo.jpg', { minPixelSize: 0 })).status).toBe('READY');
  expect((await checkFace('/photo.jpg', { minPixelSize: 0, areaThreshold: 1 })).status).toBe(
    'NO_FACE'
  );
});

it.each(['VISION_NOT_ENABLED', 'IMAGE_DECODE_FAILED', 'VISION_FAILED'] as const)(
  'normalizes native %s errors',
  async (code) => {
    platform.OS = 'android';
    native.detectFaces.mockRejectedValue(new Error(`${code}:mlkit-vision:Face check failed`));
    const error = await checkFace('content://photos/1').catch((e) => e);
    expect(error).toBeInstanceOf(ModelError);
    expect(error.code).toBe(code);
    expect(error.modelId).toBe('mlkit-vision');
  }
);

it('rejects unsupported platforms and invalid inputs without calling native', async () => {
  platform.OS = 'web';
  await expect(checkFace('/a.jpg')).rejects.toMatchObject({ code: 'DEVICE_NOT_SUPPORTED' });
  platform.OS = 'ios';
  await expect(checkFace('https://example.com/a.jpg')).rejects.toThrow();
  await expect(checkFace('/a.jpg', { areaThreshold: -1 })).rejects.toThrow();
  expect(native.detectFaces).not.toHaveBeenCalled();
});
