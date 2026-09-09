export interface NavItem {
  title: string;
  href: string;
  description: string;
  items?: NavItem[];
}

export interface NavSection {
  title: string;
  items: NavItem[];
}

// The sidebar mirrors the library's capabilities (LLM, Speech, Vision,
// Embeddings today), followed by the cross-cutting model, platform, and
// integration guides. A new capability gets its own group here.
export const navigation: NavSection[] = [
  { title: "Start", items: [
    { title: "Overview", href: "/", description: "On-device AI for React Native and Expo" },
    { title: "Installation", href: "/get-started", description: "Install, build, and run your first request" },
  ] },
  { title: "LLM", items: [
    { title: "Generate text", href: "/guides/llm", description: "sendMessage, streamMessage, system prompts, and cancellation" },
    { title: "Structured output", href: "/guides/structured-output", description: "generateObject with a JSON Schema" },
    { title: "Tool calling", href: "/guides/tool-calling", description: "generateText with tools and bounded agent loops" },
    { title: "Conversations", href: "/guides/multi-turn", description: "Pass and trim message history" },
  ] },
  { title: "Speech", items: [
    { title: "Transcribe audio", href: "/guides/speech", description: "transcribe files and streamTranscription from the microphone" },
  ] },
  { title: "Vision", items: [
    { title: "Work with images", href: "/guides/vision", description: "removeBackground, labelImage, recognizeText, cutouts and OCR" },
    { title: "Detect faces", href: "/guides/face-detection", description: "detectFaces: face boxes in normalized and pixel coordinates, profile-photo and crop recipes" },
  ] },
  { title: "Embeddings", items: [
    { title: "Search by meaning", href: "/guides/embeddings", description: "embed, chunkText, createVectorStore, semantic search and retrieval" },
  ] },
  { title: "Configuration", items: [
    { title: "Models", href: "/guides/models", description: "Built-in, downloadable, and custom LiteRT-LM models" },
    { title: "Platform support", href: "/guides/platform-support", description: "Device and OS requirements" },
    { title: "Vercel AI SDK", href: "/guides/vercel-ai-sdk", description: "Use expo-ai-kit/ai with the AI SDK" },
  ] },
  { title: "Reference & help", items: [
    { title: "API reference", href: "/api", description: "Functions, types, plugin options, and errors" },
    { title: "Examples", href: "/examples", description: "Complete React Native integrations" },
    { title: "Troubleshooting", href: "/troubleshooting", description: "Setup, downloads, and runtime errors" },
  ] },
];

// Deep reference entries stay searchable without duplicating the sidebar.
const referenceItems: NavItem[] = [
  { title: "LLM reference", href: "/api#llm", description: "isAvailable, prepareBuiltInModel, sendMessage, streamMessage, generateObject, generateText" },
  { title: "Speech reference", href: "/api#speech-to-text", description: "Speech permissions, preparation, transcription" },
  { title: "Vision reference", href: "/api#vision", description: "Image functions, face detection, availability and preparation" },
  { title: "Embeddings reference", href: "/api#embeddings", description: "Embedding lifecycle and vector search" },
  { title: "Models reference", href: "/api#model-management", description: "setModel, downloadModel, registerModel, unloadModel" },
  { title: "Config plugin", href: "/api#config-plugin", description: "speech, vision, androidEmbeddings" },
  { title: "Types", href: "/api#types", description: "TypeScript interfaces and results" },
  { title: "Errors", href: "/api#errors", description: "ModelError codes" },
  { title: "Android setup", href: "/guides/android-setup", description: "Android native configuration" },
  { title: "Migration", href: "/guides/migration", description: "Upgrade from the early session API" },
];

export interface SearchItem {
  title: string;
  href: string;
  section: string;
  description: string;
}

function flattenItems(items: NavItem[]): NavItem[] {
  return items.flatMap((item) => [item, ...(item.items ? flattenItems(item.items) : [])]);
}

// Search and the sidebar share one source of truth. Adding a page or API entry
// to navigation makes it searchable automatically.
export const searchIndex: SearchItem[] = [...navigation, { title: "Reference", items: referenceItems }].flatMap((section) =>
  flattenItems(section.items).map((item) => ({
    title: item.title,
    href: item.href,
    section: section.title,
    description: item.description,
  }))
);
