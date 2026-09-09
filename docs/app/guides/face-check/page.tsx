import Link from "next/link";
import { DocsLayout } from "@/components/DocsLayout";
import { CodeBlock } from "@/components/CodeBlock";
import { createPageMetadata } from "@/lib/site";

export const metadata = createPageMetadata("Face checks", "Check photos for one dominant face on-device in React Native.", "/guides/face-check");
const headings = [
  { id: "setup", text: "Setup", level: 2 },
  { id: "check", text: "Check a photo", level: 2 },
  { id: "thresholds", text: "Thresholds and results", level: 2 },
  { id: "migration", text: "From expo-face-check", level: 2 },
];
export default function FaceCheckPage() {
  return <DocsLayout headings={headings}>
    <h1>Check faces</h1>
    <p>Check whether a photo contains one dominant face before accepting it as a profile photo.</p>
    <h2 id="setup">Setup</h2>
    <p>Follow <Link href="/get-started">installation</Link>. On Android, enable vision and rebuild:</p>
    <CodeBlock language="json" filename="app.json">{`{
  "expo": {
    "plugins": [["expo-ai-kit", { "vision": true }]]
  }
}`}</CodeBlock>
    <p>iOS uses Apple Vision on a physical device; Simulator is not supported. Android uses bundled ML Kit Face Detection. No model download or additional permission is required. Face checks do not require the built-in LLM or Apple Intelligence.</p>
    <h2 id="check">Check a photo</h2>
    <CodeBlock language="typescript">{`import { checkFace } from 'expo-ai-kit';

const result = await checkFace(photo.uri, { minPixelSize: 500_000 });
if (result.status === 'READY') {
  // Accept the photo. Bounds are in upright image pixels.
  console.log(result.dominantFaceBounds);
} else {
  // Ask the user to choose another photo.
  console.log(result.status);
}`}</CodeBlock>
    <p>Pass a local <code>file://</code> URI or absolute path. Android also supports <code>content://</code> URIs. Remote URLs are not accepted. Multiple photos can be checked concurrently.</p>
    <h2 id="thresholds">Thresholds and results</h2>
    <table><thead><tr><th>Status</th><th>Meaning</th></tr></thead><tbody>
      <tr><td><code>READY</code></td><td>Exactly one dominant face</td></tr>
      <tr><td><code>NO_FACE</code></td><td>No face passes the dominance threshold</td></tr>
      <tr><td><code>MULTIPLE_FACES</code></td><td>More than one dominant face</td></tr>
      <tr><td><code>LOW_QUALITY</code></td><td>Image width × height is below the pixel floor</td></tr>
    </tbody></table>
    <p><code>minPixelSize</code> defaults to 500,000 total image pixels. It checks resolution, not focus, lighting, or face size.</p>
    <p><code>areaThreshold</code> defaults to 0.2. A face counts when its bounding-box area divided by the largest face area is <strong>strictly greater</strong> than this value. A small background face can therefore be ignored. Values range from 0 to 1; at 1, no face passes.</p>
    <p><code>faceCount</code> counts dominant faces. Only <code>READY</code> includes <code>dominantFaceBounds</code>: <code>{`{ x, y, width, height }`}</code> in upright image pixels, origin top-left. This preserves the expo-face-check API; other vision functions use normalized bounds.</p>
    <p>Invalid options reject before native work. Decode and detector failures throw <code>ModelError</code> with <code>IMAGE_DECODE_FAILED</code> or <code>VISION_FAILED</code>. An Android build without vision throws <code>VISION_NOT_ENABLED</code>. Detection does not identify a person or verify liveness.</p>
    <h2 id="migration">From expo-face-check</h2>
    <p>Install expo-ai-kit, remove expo-face-check, enable vision on Android, and make a new native build. Change the import:</p>
    <CodeBlock language="typescript">{`import { checkFace, type FaceCheckStatus } from 'expo-ai-kit';`}</CodeBlock>
    <p>The call signature, defaults, statuses, dominant-face count, and pixel bounds are retained. Runtime errors use expo-ai-kit&apos;s <Link href="/api#errors">ModelError codes</Link> instead of the old <code>ERR_*</code> codes. Unlike the old iOS loader, this API accepts only local images.</p>
    <p>Every expo-ai-kit capability is opt-in at build time. Leave <code>llm</code> off and no text runtime is compiled in; the app keeps its Android minimum SDK and, beyond the same bundled face detector expo-face-check used, adds only the other ML Kit vision clients and the bundled label model that the <code>vision</code> option brings. iOS adds nothing.</p>
  </DocsLayout>;
}
