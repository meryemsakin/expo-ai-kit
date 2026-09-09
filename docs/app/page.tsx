import type { Metadata } from "next";
import Link from "next/link";
import { CodeBlock } from "@/components/CodeBlock";
import { DocsLayout } from "@/components/DocsLayout";

export const metadata: Metadata = { alternates: { canonical: "/" } };

export default function Home() {
  return (
    <DocsLayout>
      <h1>On-device AI for React Native.</h1>
      <p className="text-xl text-muted leading-relaxed">
        Generate text, transcribe speech, work with images, and search by meaning.
        Runs on iOS and Android. No API key.
      </p>
      <p>
        Use it in an Expo app or a React Native app with Expo modules installed.
        You need a native build; Expo Go is not supported.
      </p>
      <CodeBlock language="bash">{`npx expo install expo-ai-kit`}</CodeBlock>
      <p><Link href="/get-started">Installation and first run →</Link></p>

      <h2 id="capabilities">What do you want to build?</h2>
      <table>
        <thead><tr><th>Capability</th><th>Use it for</th></tr></thead>
        <tbody>
          <tr><td><Link href="/guides/llm">LLM</Link></td><td>Chat, text generation, structured JSON, and tool calling</td></tr>
          <tr><td><Link href="/guides/speech">Speech</Link></td><td>Live dictation and audio transcription</td></tr>
          <tr><td><Link href="/guides/vision">Vision</Link></td><td>Background removal, image labels, OCR, and <Link href="/guides/face-check">face checks</Link></td></tr>
          <tr><td><Link href="/guides/embeddings">Embeddings</Link></td><td>Semantic search over your own data</td></tr>
        </tbody>
      </table>

      <h2 id="quick-start">A text request</h2>
      <CodeBlock language="typescript" filename="ask.ts">{`import { isAvailable, prepareBuiltInModel, sendMessage } from 'expo-ai-kit';

export async function ask(question: string) {
  if (!(await isAvailable())) return 'Built-in text model unavailable.';
  await prepareBuiltInModel();
  const { text } = await sendMessage([{ role: 'user', content: question }]);
  return text;
}`}</CodeBlock>
      <p>
        Support depends on the device and feature. The built-in text model needs
        Apple Intelligence on iOS 26+ or a supported Android device. Other features
        have their own requirements. See <Link href="/guides/platform-support">platform support</Link> or
        use a <Link href="/guides/models">downloadable model</Link>.
      </p>
      <p>
        Already using the <Link href="/guides/vercel-ai-sdk">Vercel AI SDK</Link>?
        Import the provider from <code>expo-ai-kit/ai</code>.
      </p>
    </DocsLayout>
  );
}
