# expo-ai-kit

**On-device AI for React Native.** Generate text, transcribe speech, work with images,
and search by meaning. Supports Expo apps and React Native apps with Expo modules installed.
Inference runs on the device, with no API key.

[Documentation](https://expo-ai-kit.dev) · [Examples](https://expo-ai-kit.dev/examples) ·
[npm](https://www.npmjs.com/package/expo-ai-kit) · [Changelog](./CHANGELOG.md)

## Install

```bash
npx expo install expo-ai-kit
```

Requires Expo SDK 54+ (or compatible Expo modules in an existing React Native app) and a
native development or production build. **Expo Go is not supported.** Follow
[Get Started](https://expo-ai-kit.dev/get-started) for native setup and a working app example.
Device and OS requirements vary by feature; see [platform support](https://expo-ai-kit.dev/guides/platform-support).

## First request

On a device with a supported built-in text model:

```ts
import { isAvailable, prepareBuiltInModel, sendMessage } from 'expo-ai-kit';

async function ask(question: string) {
  if (!(await isAvailable())) {
    return 'The built-in text model is unavailable on this device.';
  }
  await prepareBuiltInModel();
  const { text } = await sendMessage([{ role: 'user', content: question }]);
  return text;
}

const answer = await ask('Explain gravity in one sentence.');
```

The built-in text model requires Apple Intelligence on iOS 26+ or a supported Android device.
Android may download its model during preparation. You can also
[download a model](https://expo-ai-kit.dev/guides/models) such as Gemma, Qwen, or Phi.

## LLM

[Generate and stream text](https://expo-ai-kit.dev/guides/llm),
[return structured JSON](https://expo-ai-kit.dev/guides/structured-output), or
[let the model call your functions](https://expo-ai-kit.dev/guides/tool-calling).
Pass the full message history on each call. One generation runs at a time.

## Speech

[Transcribe microphone audio or recorded files](https://expo-ai-kit.dev/guides/speech)
with `streamTranscription` and `transcribe`. Enable the `speech` plugin option and rebuild.
iOS requires 26+; Android requires 12+ and processes file audio at real-time rate.

## Vision

[Remove backgrounds, label images, and read text](https://expo-ai-kit.dev/guides/vision)
with `removeBackground`, `labelImage`, and `recognizeText`.
[Check a photo for one dominant face](https://expo-ai-kit.dev/guides/face-check) with `checkFace`:

```ts
import { checkFace } from 'expo-ai-kit';

const result = await checkFace(photo.uri, { minPixelSize: 500_000 });
// result.status: READY | NO_FACE | MULTIPLE_FACES | LOW_QUALITY
```

Enable `vision` on Android and rebuild. Face checks use a bundled detector and need no
model download. The API keeps `expo-face-check`'s thresholds, statuses, and pixel bounds.

## Embeddings

[Semantic search and retrieval](https://expo-ai-kit.dev/guides/embeddings) with
`embed`, `chunkText`, and `createVectorStore`. iOS uses the system embedding model;
Android requires the `androidEmbeddings` option and an explicit model download.

## Optional features

Add only the features your app uses, then make a new native build. For example, to enable
Android vision:

```json
{
  "expo": {
    "plugins": [["expo-ai-kit", { "vision": true }]]
  }
}
```

The [config reference](https://expo-ai-kit.dev/api#config-plugin) covers all options.
Speech adds microphone permissions; vision adds none. Enabling vision bundles the Android
face and image-labeling models; background removal and OCR use models prepared separately.

## More

- [Vercel AI SDK](https://expo-ai-kit.dev/guides/vercel-ai-sdk): use `expo-ai-kit/ai` with the AI SDK.
- [API reference](https://expo-ai-kit.dev/api): functions, options, results, and typed errors.
- [Troubleshooting](https://expo-ai-kit.dev/troubleshooting) and [GitHub issues](https://github.com/saidkaban/expo-ai-kit/issues).
- [llms.txt](https://expo-ai-kit.dev/llms.txt): documentation index and essential contracts for coding agents.

Zero runtime JavaScript dependencies. MIT © [Said Kaban](https://github.com/saidkaban).
