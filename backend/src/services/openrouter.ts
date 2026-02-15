import {
  TRIAGE_SYSTEM_PROMPT,
  VISUAL_ANALYSIS_SYSTEM_PROMPT,
  TEXT_ANALYSIS_SYSTEM_PROMPT,
} from "../prompts.js";
import type {
  TriageResult,
  AnalysisResult,
  Highlight,
  UiTree,
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

export async function triageQuery(
  userText: string,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal
): Promise<{ needsScreenshot: boolean; reason: string; requestSpeech: string }> {
  const messages: ChatMessage[] = [
    { role: "system", content: TRIAGE_SYSTEM_PROMPT },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
      content: h.content,
    })),
    { role: "user", content: userText },
  ];

  const raw = await chatCompletion(messages, TEXT_MODEL, 15_000, signal);
  const parsed = parseJSON<{ needsScreenshot: boolean; reason: string; requestSpeech?: string }>(raw);
  return {
    needsScreenshot: parsed.needsScreenshot,
    reason: parsed.reason,
    requestSpeech: parsed.requestSpeech ?? "",
  };
}

export async function visualAnalysis(
  userText: string,
  screenshotBase64: string,
  uiTree: UiTree,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal
): Promise<AnalysisResult> {
  const messages: ChatMessage[] = [
    { role: "system", content: VISUAL_ANALYSIS_SYSTEM_PROMPT },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
      content: h.content,
    })),
    {
      role: "user",
      content: [
        {
          type: "text",
          text: `User question: ${userText}\n\nUI Tree:\n${JSON.stringify(uiTree, null, 2)}`,
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

  const raw = await chatCompletion(messages, VISION_MODEL, 30_000, signal);
  return parseJSON<AnalysisResult>(raw);
}

export async function textAnalysis(
  userText: string,
  uiTree: UiTree,
  conversationHistory: Array<{ role: "user" | "assistant"; content: string }>,
  signal?: AbortSignal
): Promise<AnalysisResult> {
  const messages: ChatMessage[] = [
    { role: "system", content: TEXT_ANALYSIS_SYSTEM_PROMPT },
    ...conversationHistory.map((h) => ({
      role: h.role as "user" | "assistant",
      content: h.content,
    })),
    {
      role: "user",
      content: `User question: ${userText}\n\nUI Tree:\n${JSON.stringify(uiTree, null, 2)}`,
    },
  ];

  const raw = await chatCompletion(messages, TEXT_MODEL, 30_000, signal);
  return parseJSON<AnalysisResult>(raw);
}
