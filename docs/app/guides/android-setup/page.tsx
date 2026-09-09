import Link from "next/link";
import { DocsLayout } from "@/components/DocsLayout";
import { CodeBlock } from "@/components/CodeBlock";
import { createPageMetadata } from "@/lib/site";

export const metadata = createPageMetadata("Android setup", "Configure native Android builds for expo-ai-kit.", "/guides/android-setup");
export default function AndroidSetupPage() {
  return <DocsLayout>
    <h1>Android setup</h1>
    <p>Follow <Link href="/get-started">Get Started</Link> to install and build. All features are off by default: the library compiles only the options you turn on. It follows your app&apos;s <code>minSdkVersion</code> (Expo&apos;s floor is 24); the <code>llm</code> and <code>speech</code> options raise it to 26.</p>
    <h2 id="configuration">Build options</h2>
    <p>Enable only what your app uses. For example, to add image operations and face checks:</p>
    <CodeBlock language="json" filename="app.json">{`{
  "expo": {
    "plugins": [["expo-ai-kit", { "vision": true }]]
  }
}`}</CodeBlock>
    <table><thead><tr><th>Option</th><th>What it adds</th></tr></thead><tbody>
      <tr><td><code>llm</code></td><td>Text generation and downloadable models: the ML Kit Prompt API client and the LiteRT-LM runtime (about 21 MB of arm64 code); <code>minSdkVersion</code> 26</td></tr>
      <tr><td><code>speech</code></td><td>Speech recognition and microphone permission, including for file transcription</td></tr>
      <tr><td><code>vision</code></td><td>Image operations, bundled label and face models; no permissions</td></tr>
      <tr><td><code>androidEmbeddings</code></td><td>EmbeddingGemma runtime; model downloaded separately</td></tr>
    </tbody></table>
    <p>Every option change requires a new native build. An OTA update cannot add native modules or models bundled with the app.</p>
    <h2 id="bare">Bare React Native</h2>
    <p>Without Expo prebuild, set the relevant properties directly in <code>android/gradle.properties</code>:</p>
    <CodeBlock language="properties">{`# Enable text generation:
expoAiKit.llm=true
# Enable image operations and face checks:
expoAiKit.vision=true`}</CodeBlock>
    <p>The other properties are <code>expoAiKit.speech</code> and <code>expoAiKit.androidEmbeddings</code>. Speech also needs <code>android.permission.RECORD_AUDIO</code> in your manifest. The <code>llm</code> and <code>speech</code> options need <code>minSdkVersion</code> 26. iOS reads <code>expoAiKit.llm</code> from <code>ios/Podfile.properties.json</code>; see the <Link href="/api#config-plugin">config reference</Link>.</p>
    <h2 id="models">Model preparation</h2>
    <p>Await <code>prepareBuiltInModel()</code> for built-in text. Vision background removal and OCR need <code>prepareVision()</code> and Google Play services. Image labels and face checks work immediately after the native build.</p>
    <p>See <Link href="/guides/speech">speech</Link>, <Link href="/guides/vision">vision</Link>, <Link href="/guides/embeddings">embeddings</Link>, and <Link href="/guides/models">downloadable models</Link> for their lifecycles. For device restrictions and errors, see <Link href="/guides/platform-support">platform support</Link> and <Link href="/troubleshooting">troubleshooting</Link>.</p>
  </DocsLayout>;
}
