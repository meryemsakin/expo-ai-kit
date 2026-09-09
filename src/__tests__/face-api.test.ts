import { Platform } from 'react-native';

import ExpoAiKitModule from '../ExpoAiKitModule';
import { detectFaces, ModelError } from '../index';

jest.mock('react-native', () => ({ Platform: { OS: 'ios' } }));
jest.mock('../ExpoAiKitModule', () => ({ __esModule: true, default: { detectFaces: jest.fn() } }));
const native = ExpoAiKitModule as jest.Mocked<typeof ExpoAiKitModule>;
const platform = Platform as { OS: string };
beforeEach(() => {
  jest.resetAllMocks();
  platform.OS = 'ios';
});

it('runs concurrently and returns normalized faces', async () => {
  native.detectFaces.mockResolvedValue({
    width: 200,
    height: 100,
    faces: [{ x: 20, y: 10, width: 40, height: 40 }],
  });
  const results = await Promise.all(
    Array.from({ length: 4 }, () => detectFaces({ uri: 'file:///photo.jpg' }))
  );
  expect(native.detectFaces).toHaveBeenCalledTimes(4);
  expect(native.detectFaces).toHaveBeenCalledWith('file:///photo.jpg');
  expect(results[0]).toEqual({
    width: 200,
    height: 100,
    faces: [
      {
        bounds: { x: 0.1, y: 0.1, width: 0.2, height: 0.4 },
        pixelBounds: { x: 20, y: 10, width: 40, height: 40 },
      },
    ],
  });
});

it.each([
  'VISION_NOT_ENABLED',
  'IMAGE_DECODE_FAILED',
  'VISION_FAILED',
  'DEVICE_NOT_SUPPORTED',
] as const)('normalizes native %s errors', async (code) => {
  platform.OS = 'android';
  native.detectFaces.mockRejectedValue(new Error(`${code}:mlkit-vision:Face detection failed`));
  const error = await detectFaces({ uri: 'content://photos/1' }).catch((e) => e);
  expect(error).toBeInstanceOf(ModelError);
  expect(error.code).toBe(code);
  expect(error.modelId).toBe('mlkit-vision');
});

it('rejects unsupported platforms and invalid inputs without calling native', async () => {
  platform.OS = 'web';
  await expect(detectFaces({ uri: '/a.jpg' })).rejects.toMatchObject({
    code: 'DEVICE_NOT_SUPPORTED',
  });
  platform.OS = 'ios';
  await expect(detectFaces({ uri: 'https://example.com/a.jpg' })).rejects.toThrow();
  await expect(detectFaces({} as never)).rejects.toThrow();
  expect(native.detectFaces).not.toHaveBeenCalled();
});
