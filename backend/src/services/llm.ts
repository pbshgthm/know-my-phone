import {
  createProviderRegistry,
  generateText,
  streamText,
  type ModelMessage,
} from "ai";
import { anthropic } from "@ai-sdk/anthropic";
import { google } from "@ai-sdk/google";
import {
  getTriagePrompt,
  getVisualAnalysisPrompt,
  getTextAnalysisPrompt,
} from "../prompts.js";
import type {
  UiTree,
  RedactionInfo,
  VisualRedactionInfo,
} from "../protocol.js";

const providerRegistry = createProviderRegistry(
  {
    anthropic,
    google,
  },
  { separator: "/" }
);

// Preserve OpenRouter-style aliases while routing through native provider IDs.
const MODEL_ALIASES: Record<string, string> = {
  "anthropic/claude-4.5-haiku": "anthropic/claude-haiku-4-5",
};

// Model configuration from environment or defaults.
const TEXT_MODEL = process.env.LLM_TEXT_MODEL || "anthropic/claude-4.5-haiku";
const VISION_MODEL = process.env.LLM_VISION_MODEL || "anthropic/claude-4.5-haiku";

interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
}

type ProviderModelId = `anthropic/${string}` | `google/${string}`;

function resolveModelId(modelId: string): string {
  return MODEL_ALIASES[modelId] ?? modelId;
}

function toProviderModelId(modelId: string): ProviderModelId {
  if (modelId.startsWith("anthropic/") || modelId.startsWith("google/")) {
    return modelId as ProviderModelId;
  }
  throw new Error(`Unsupported LLM provider in "${modelId}". Use anthropic/* or google/*.`);
}

function ensureProviderKey(modelId: string): void {
  const provider = resolveModelId(modelId).split("/")[0];
  if (provider === "anthropic" && !process.env.ANTHROPIC_API_KEY) {
    throw new Error("ANTHROPIC_API_KEY not set");
  }
  if (provider === "google" && !process.env.GOOGLE_GENERATIVE_AI_API_KEY) {
    throw new Error("GOOGLE_GENERATIVE_AI_API_KEY not set");
  }
}

function getModel(modelId: string) {
  const resolved = resolveModelId(modelId);
  try {
    return providerRegistry.languageModel(toProviderModelId(resolved));
  } catch {
    throw new Error(`Unsupported LLM model "${modelId}". Expected format "provider/model".`);
  }
}

function withTimeout(signal: AbortSignal | undefined, timeoutMs: number): AbortSignal {
  return signal
    ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)])
    : AbortSignal.timeout(timeoutMs);
}

async function chatCompletion(
  messages: ModelMessage[],
  model: string,
  timeoutMs: number,
  signal?: AbortSignal
): Promise<string> {
  ensureProviderKey(model);
  console.log(`[LLM] 🤖 Calling ${model} with ${messages.length} messages...`);

  const { text } = await generateText({
    model: getModel(model),
    messages,
    temperature: 0.3,
    maxOutputTokens: 1024,
    abortSignal: withTimeout(signal, timeoutMs),
  });

  if (!text || text.trim().length === 0) {
    throw new Error("LLM returned empty or malformed response");
  }
  console.log(`[LLM] ✅ Response received (${text.length} chars)`);
  return text;
}

function parseJSON<T>(raw: string): T {
  // Strip markdown code fences if present.
  let cleaned = raw.trim();
  if (cleaned.startsWith("```")) {
    cleaned = cleaned.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
  }
  return JSON.parse(cleaned) as T;
}

function buildVisualAnalysisUserText(
  userText: string,
  uiTree: UiTree,
  redactions?: RedactionInfo[],
  visualRedactions?: VisualRedactionInfo[]
): string {
  let text = `User question: ${userText}\n\nUI Tree:\n${JSON.stringify(uiTree, null, 2)}`;

  const hasTextRedactions = redactions && redactions.length > 0;
  const hasVisualRedactions = visualRedactions && visualRedactions.length > 0;

  if (hasTextRedactions || hasVisualRedactions) {
    text += "\n\n⚠️ PRIVACY REDACTION NOTICE — Sensitive information was detected and redacted BEFORE being sent to you. DO NOT attempt to guess or reconstruct the redacted content.";

    if (hasTextRedactions) {
      text += "\n\nText redactions (in UI tree, replaced with [REDACTED:...] tokens):";
      for (const r of redactions!) {
        const label = r.type.replace("[REDACTED:", "").replace("]", "");
        const nodeInfo = r.nodeIds.length > 0
          ? ` in nodes [${r.nodeIds.join(", ")}]`
          : "";
        text += `\n- ${label}: ${r.count} instance(s)${nodeInfo}`;
      }
    }

    if (hasVisualRedactions) {
      text += "\n\nVisual redactions (black boxes with [REDACTED: ...] labels drawn on the screenshot):";
      for (const vr of visualRedactions!) {
        const { left, top, right, bottom } = vr.bounds;
        text += `\n- ${vr.label} at screen region [${left},${top} → ${right},${bottom}]`;
      }
    }

    text += "\n\nIf the user asks about content in a redacted area, tell them it was automatically hidden for privacy.";
  }
  return text;
}

// --- Public APIs ---

export async function triageQuery(
  userText: string,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal,
  autoScreenshot: boolean = false,
  deviceInfo?: DeviceInfo
): Promise<{ needsScreenshot: boolean; reason: string; spokenRequest?: string; confirmLabel?: string }> {
  const messages: ModelMessage[] = [
    { role: "system", content: getTriagePrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role,
      content: h.content,
    })),
    { role: "user", content: userText },
  ];

  const raw = await chatCompletion(messages, TEXT_MODEL, 15_000, signal);
  const parsed = parseJSON<{ needsScreenshot: boolean; reason: string; spokenRequest?: string; confirmLabel?: string }>(raw);
  return {
    needsScreenshot: parsed.needsScreenshot,
    reason: parsed.reason,
    spokenRequest: parsed.spokenRequest,
    confirmLabel: parsed.confirmLabel,
  };
}

async function* streamChatCompletion(
  messages: ModelMessage[],
  model: string,
  timeoutMs: number,
  signal?: AbortSignal
): AsyncGenerator<string> {
  ensureProviderKey(model);
  console.log(`[LLM] 🤖 Streaming ${model} with ${messages.length} messages...`);

  let streamError: Error | null = null;
  const result = streamText({
    model: getModel(model),
    messages,
    temperature: 0.3,
    maxOutputTokens: 1024,
    abortSignal: withTimeout(signal, timeoutMs),
    onError: ({ error }) => {
      streamError = error instanceof Error ? error : new Error(String(error));
    },
  });

  for await (const chunk of result.textStream) {
    if (chunk.length > 0) {
      yield chunk;
    }
  }

  if (streamError) {
    throw streamError;
  }
}

export async function* streamVisualAnalysis(
  userText: string,
  screenshotBase64: string,
  uiTree: UiTree,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal,
  autoScreenshot: boolean = false,
  redactions?: RedactionInfo[],
  deviceInfo?: DeviceInfo,
  visualRedactions?: VisualRedactionInfo[]
): AsyncGenerator<string> {
  const messages: ModelMessage[] = [
    { role: "system", content: getVisualAnalysisPrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role,
      content: h.content,
    })),
    {
      role: "user",
      content: [
        {
          type: "text",
          text: buildVisualAnalysisUserText(userText, uiTree, redactions, visualRedactions),
        },
        {
          type: "image",
          image: `data:image/jpeg;base64,${screenshotBase64}`,
        },
      ],
    },
  ];

  yield* streamChatCompletion(messages, VISION_MODEL, 30_000, signal);
}

export async function* streamTextAnalysis(
  userText: string,
  uiTree: UiTree,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal,
  autoScreenshot: boolean = false,
  deviceInfo?: DeviceInfo
): AsyncGenerator<string> {
  const messages: ModelMessage[] = [
    { role: "system", content: getTextAnalysisPrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role,
      content: h.content,
    })),
    {
      role: "user",
      content: `User question: ${userText}\n\nUI Tree:\n${JSON.stringify(uiTree, null, 2)}`,
    },
  ];

  yield* streamChatCompletion(messages, TEXT_MODEL, 30_000, signal);
}
