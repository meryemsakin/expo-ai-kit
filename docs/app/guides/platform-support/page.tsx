import Link from "next/link";
import { DocsLayout } from "@/components/DocsLayout";
import { createPageMetadata } from "@/lib/site";

export const metadata = createPageMetadata("Platform support", "Device, OS, and native build requirements for React Native on-device AI.", "/guides/platform-support");
export default function PlatformSupportPage() {
  return <DocsLayout>
    <h1>Platform support</h1>
    <p>Install in an Expo SDK 54+ app or a React Native app with compatible Expo modules. The library minimum is iOS 15.1 and Android API 24; the <code>llm</code> and <code>speech</code> options require Android API 26. Every capability is opt-in at build time, so requirements apply only to the options you enable. Individual features may require a newer OS or specific hardware.</p>
    <p>Use a native development or production build. Expo Go and web are not supported.</p>
    <h2 id="feature-comparison">Requirements by feature</h2>
    <table><thead><tr><th>Feature</th><th>iOS</th><th>Android</th></tr></thead><tbody>
      <tr><td>LLM: built-in</td><td>Enable <code>llm</code>; iOS 26+, Apple Intelligence device with Apple Intelligence enabled and model ready</td><td>Enable <code>llm</code>; ML Kit Prompt API on <a href="https://developers.google.com/ml-kit/genai#prompt-device">supported devices</a></td></tr>
      <tr><td>LLM: downloadable</td><td>Enable <code>llm</code>; LiteRT-LM with enough memory for the selected model</td><td>Enable <code>llm</code>; LiteRT-LM on arm64 with enough memory for the selected model</td></tr>
      <tr><td>Speech</td><td>iOS 26+, SpeechAnalyzer</td><td>Android 12+, ML Kit Speech Recognition; enable <code>speech</code></td></tr>
      <tr><td>Vision: background removal</td><td>iOS 17+, physical device</td><td>Enable <code>vision</code>; Google Play services model</td></tr>
      <tr><td>Vision: image labels</td><td>Physical device</td><td>Enable <code>vision</code>; bundled model</td></tr>
      <tr><td>Vision: OCR</td><td>Apple Vision; also works in Simulator</td><td>Enable <code>vision</code>; Google Play services script models</td></tr>
      <tr><td>Vision: face detection</td><td>Apple Vision on a physical device; no Apple Intelligence requirement</td><td>Enable <code>vision</code>; bundled detector</td></tr>
      <tr><td>Embeddings</td><td>iOS 17+, NLContextualEmbedding</td><td>Enable <code>androidEmbeddings</code>; prepare EmbeddingGemma</td></tr>
    </tbody></table>
    <p>All LLM functions—text, streaming, structured output, and tool calling—use the selected text model. Vision and speech do not require an available LLM.</p>
    <h2 id="availability">Check at runtime</h2>
    <p>Use <code>isAvailable()</code> for built-in text, <code>getSpeechRecognitionAvailability()</code> for speech, <code>getVisionAvailability()</code> for images, and <code>getEmbeddingModelStatus()</code> for embeddings. A library-compatible OS alone does not guarantee model availability.</p>
    <p>Android built-in text can be supported before its model is downloaded. Await <code>prepareBuiltInModel()</code> before generation. For other features, follow their preparation steps.</p>
    <h2 id="testing">Testing and fallbacks</h2>
    <p>Test on physical devices. iOS Simulator cannot run background removal, image labeling, or face detection. Downloadable text models are disabled on Android x86/x86_64 because the LiteRT-LM backend can crash; use arm64.</p>
    <p>When built-in text is unavailable, offer a <Link href="/guides/models">downloadable model</Link> or a fallback in your UI. Native failures surface as <Link href="/api#errors">typed errors</Link>.</p>
    <p>See <Link href="/guides/android-setup">Android setup</Link> for build configuration and <Link href="/troubleshooting">troubleshooting</Link> for failures.</p>
  </DocsLayout>;
}
