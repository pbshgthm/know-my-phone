import {
  getTriagePrompt,
  getVisualAnalysisPrompt,
  getTextAnalysisPrompt,
} from "../prompts.js";
import type {
  TriageResult,
  AnalysisResult,
  Highlight,
  UiTree,
  RedactionInfo,
  VisualRedactionInfo,
} from "../protocol.js";

const OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1";

// Model configuration from environment or defaults
const TEXT_MODEL = process.env.LLM_TEXT_MODEL || "openai/gpt-5.2";
const VISION_MODEL = process.env.LLM_VISION_MODEL || "openai/gpt-5.2";

// Available models:
// - openai/gpt-5.2 (latest, if available via preview access)
// - openai/gpt-4o (best quality, widely available)
// - openai/gpt-4-turbo (good quality, faster)
// - google/gemini-2.0-flash-001 (fast, cheap)
// - anthropic/claude-3.5-sonnet (excellent quality)

interface ChatMessage {
  role: "system" | "user" | "assistant";
  content: string | Array<{ type: string; text?: string; image_url?: { url: string } }>;
}

async function chatCompletion(
  messages: ChatMessage[],
  model: string,
  timeoutMs: number,
  signal?: AbortSignal
): Promise<string> {
  const apiKey = process.env.OPENROUTER_API_KEY;
  if (!apiKey) {
    console.error(`[OpenRouter] ❌ OPENROUTER_API_KEY not set in environment`);
    throw new Error("OPENROUTER_API_KEY not set");
  }

  console.log(`[OpenRouter] 🤖 Calling ${model} with ${messages.length} messages...`);

  const fetchSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)])
    : AbortSignal.timeout(timeoutMs);

  const response = await fetch(`${OPENROUTER_BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://know-your-phone.app",
      "X-Title": "Know Your Phone",
    },
    body: JSON.stringify({
      model,
      messages,
      temperature: 0.3,
      max_tokens: 1024,
    }),
    signal: fetchSignal,
  });

  if (!response.ok) {
    const text = await response.text();
    console.error(`[OpenRouter] ❌ API error ${response.status}: ${text}`);
    throw new Error(`OpenRouter API error ${response.status}: ${text}`);
  }

  const data = (await response.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
  };

  const content = data.choices?.[0]?.message?.content;
  if (!content) {
    console.error(`[OpenRouter] ❌ Missing content in response:`, JSON.stringify(data));
    throw new Error("OpenRouter returned empty or malformed response");
  }
  console.log(`[OpenRouter] ✅ Response received (${content.length} chars)`);

  return content;
}

function parseJSON<T>(raw: string): T {
  // Strip markdown code fences if present
  let cleaned = raw.trim();
  if (cleaned.startsWith("```")) {
    cleaned = cleaned.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
  }
  return JSON.parse(cleaned) as T;
}

interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
}

export async function triageQuery(
  userText: string,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal,
  autoScreenshot: boolean = false,
  deviceInfo?: DeviceInfo
): Promise<{ needsScreenshot: boolean; reason: string }> {
  const messages: ChatMessage[] = [
    { role: "system", content: getTriagePrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
      content: h.content,
    })),
    { role: "user", content: userText },
  ];

  const raw = await chatCompletion(messages, TEXT_MODEL, 15_000, signal);
  const parsed = parseJSON<{ needsScreenshot: boolean; reason: string }>(raw);
  return {
    needsScreenshot: parsed.needsScreenshot,
    reason: parsed.reason,
  };
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

// --- Streaming variants ---

async function* streamChatCompletion(
  messages: ChatMessage[],
  model: string,
  timeoutMs: number,
  signal?: AbortSignal
): AsyncGenerator<string> {
  const apiKey = process.env.OPENROUTER_API_KEY;
  if (!apiKey) {
    throw new Error("OPENROUTER_API_KEY not set");
  }

  console.log(`[OpenRouter] 🤖 Streaming ${model} with ${messages.length} messages...`);

  const fetchSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)])
    : AbortSignal.timeout(timeoutMs);

  const response = await fetch(`${OPENROUTER_BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://know-your-phone.app",
      "X-Title": "Know Your Phone",
    },
    body: JSON.stringify({
      model,
      messages,
      temperature: 0.3,
      max_tokens: 1024,
      stream: true,
    }),
    signal: fetchSignal,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(`OpenRouter API error ${response.status}: ${text}`);
  }

  if (!response.body) {
    throw new Error("OpenRouter returned no stream body");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      // Keep the last potentially incomplete line in buffer
      buffer = lines.pop() || "";

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed || !trimmed.startsWith("data: ")) continue;
        const data = trimmed.slice(6);
        if (data === "[DONE]") return;

        try {
          const parsed = JSON.parse(data) as {
            choices?: Array<{ delta?: { content?: string } }>;
          };
          const content = parsed.choices?.[0]?.delta?.content;
          if (content) {
            yield content;
          }
        } catch {
          // Skip malformed SSE chunks
        }
      }
    }
  } finally {
    reader.releaseLock();
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
  const messages: ChatMessage[] = [
    { role: "system", content: getVisualAnalysisPrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
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
          type: "image_url",
          image_url: {
            url: `data:image/jpeg;base64,${screenshotBase64}`,
          },
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
  const messages: ChatMessage[] = [
    { role: "system", content: getTextAnalysisPrompt(autoScreenshot, deviceInfo) },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
      content: h.content,
    })),
    {
      role: "user",
      content: `User question: ${userText}\n\nUI Tree:\n${JSON.stringify(uiTree, null, 2)}`,
    },
  ];

  yield* streamChatCompletion(messages, TEXT_MODEL, 30_000, signal);
}
