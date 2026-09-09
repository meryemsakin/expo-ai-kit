import Link from "next/link";
import { DocsLayout } from "@/components/DocsLayout";
import { CodeBlock } from "@/components/CodeBlock";
import { createPageMetadata } from "@/lib/site";

export const metadata = createPageMetadata("Face detection", "Find faces in photos on-device in React Native.", "/guides/face-detection");
const headings = [
  { id: "setup", text: "Setup", level: 2 },
  { id: "detect", text: "Detect faces", level: 2 },
  { id: "result", text: "The result", level: 2 },
  { id: "recipes", text: "Recipes", level: 2 },
  { id: "errors", text: "Errors", level: 2 },
];
export default function FaceDetectionPage() {
  return <DocsLayout headings={headings}>
    <h1>Detect faces</h1>
    <p>Find every face in a photo and get its box. The library only detects; what counts as a good photo is your decision, and the recipes below show the common ones.</p>
    <h2 id="setup">Setup</h2>
    <p>Follow <Link href="/get-started">installation</Link>. On Android, enable vision and rebuild:</p>
    <CodeBlock language="json" filename="app.json">{`{
  "expo": {
    "plugins": [["expo-ai-kit", { "vision": true }]]
  }
}`}</CodeBlock>
    <p>iOS uses Apple Vision on a physical device; Simulator is not supported. Android uses bundled ML Kit Face Detection. No model download, preparation, or permission is required, and the <code>llm</code> option is not needed.</p>
    <h2 id="detect">Detect faces</h2>
    <CodeBlock language="typescript">{`import { detectFaces } from 'expo-ai-kit';

const { width, height, faces } = await detectFaces({ uri: photo.uri });
console.log(\`\${faces.length} face(s) in a \${width}×\${height} image\`);
for (const face of faces) {
  console.log(face.bounds, face.pixelBounds);
}`}</CodeBlock>
    <p>Pass a local <code>file://</code> URI or absolute path. Android also accepts <code>content://</code> URIs. Remote URLs are rejected. Detection runs at full resolution on the upright image (EXIF orientation applied), and calls may run concurrently.</p>
    <h2 id="result">The result</h2>
    <CodeBlock language="typescript">{`type FaceDetectionResult = {
  width: number;          // upright image size in pixels
  height: number;
  faces: DetectedFace[];  // largest first; [] when none
};
type DetectedFace = {
  bounds: { x: number; y: number; width: number; height: number };      // normalized 0–1, origin top-left
  pixelBounds: { x: number; y: number; width: number; height: number }; // upright image pixels
  confidence?: number;    // 0–1, Apple Vision only
};`}</CodeBlock>
    <p>Both coordinate spaces describe the same box, clamped to the image. Use <code>bounds</code> to draw over a scaled preview and <code>pixelBounds</code> to crop the original. Faces are sorted by area, so <code>faces[0]</code> is the largest.</p>
    <h2 id="recipes">Recipes</h2>
    <p><strong>Accept a profile photo</strong> when it is big enough and shows one clearly dominant face, ignoring small faces in the background:</p>
    <CodeBlock language="typescript">{`type PhotoStatus = 'READY' | 'NO_FACE' | 'MULTIPLE_FACES' | 'LOW_QUALITY';

async function checkProfilePhoto(uri: string): Promise<PhotoStatus> {
  const { width, height, faces } = await detectFaces({ uri });
  if (width * height < 500_000) return 'LOW_QUALITY';
  if (faces.length === 0) return 'NO_FACE';
  const area = (f: (typeof faces)[number]) => f.pixelBounds.width * f.pixelBounds.height;
  const largest = area(faces[0]);
  const dominant = faces.filter((f) => area(f) / largest > 0.2).length;
  return dominant === 1 ? 'READY' : 'MULTIPLE_FACES';
}`}</CodeBlock>
    <p><strong>Crop to the largest face</strong> with some margin, for an avatar thumbnail:</p>
    <CodeBlock language="typescript">{`const { width, height, faces } = await detectFaces({ uri });
if (faces[0]) {
  const { x, y, width: w, height: h } = faces[0].pixelBounds;
  const margin = Math.round(Math.max(w, h) * 0.4);
  const crop = {
    originX: Math.max(0, x - margin),
    originY: Math.max(0, y - margin),
    width: Math.min(width, x + w + margin) - Math.max(0, x - margin),
    height: Math.min(height, y + h + margin) - Math.max(0, y - margin),
  };
  // Pass \`crop\` to your image manipulator of choice.
}`}</CodeBlock>
    <p><strong>Count people in a frame</strong>: <code>faces.length</code>, optionally ignoring boxes below a size you choose.</p>
    <h2 id="errors">Errors</h2>
    <p>Invalid input rejects before native work. Decode and detector failures throw <code>ModelError</code> with <code>IMAGE_DECODE_FAILED</code> or <code>VISION_FAILED</code>. An Android build without vision throws <code>VISION_NOT_ENABLED</code>; the iOS Simulator throws <code>DEVICE_NOT_SUPPORTED</code>. Detection does not identify a person or verify liveness.</p>
  </DocsLayout>;
}
