import { mkdir, writeFile, readFile, readdir, rm } from "fs/promises";
import { join } from "path";
import type { Highlight } from "./protocol.js";

// Data directory at project root (server runs from backend/)
const DATA_DIR = process.env.DATA_DIR || join(process.cwd(), "..", "data");

export interface TurnData {
  turnId: number;
  autoScreenshot?: boolean;

  input: {
    transcript?: string;
    audioFile?: string;
  };

  output: {
    text?: string;
    audioFile?: string;
    highlights?: Highlight[];
  };

  triage?: {
    needsScreenshot: boolean;
    reason: string;
  };

  screenshot?: {
    file?: string;
    uiTreeFile?: string;
  };

  timing: {
    audioReceivedAt?: string;
    sttStartedAt?: string;
    sttCompletedAt?: string;
    triageStartedAt?: string;
    triageCompletedAt?: string;
    screenshotRequestedAt?: string;
    screenshotReceivedAt?: string;
    llmStartedAt?: string;
    llmFirstTokenAt?: string;
    llmCompletedAt?: string;
    ttsFirstAudioAt?: string;
    completedAt?: string;
    sentenceCount?: number;
  };
}

export interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
}

export interface ConversationData {
  clientId: string;
  sessionId: string;
  language: string;
  deviceInfo: DeviceInfo;
  createdAt: string;
  updatedAt: string;
  turns: TurnData[];
}

function convDir(clientId: string, sessionId: string): string {
  return join(DATA_DIR, clientId, sessionId);
}

async function ensureDir(dir: string): Promise<void> {
  await mkdir(dir, { recursive: true });
}

// --- Write lock: serializes reads/writes per conversation ---

const writeLocks = new Map<string, Promise<void>>();

function withWriteLock(convKey: string, fn: () => Promise<void>): Promise<void> {
  const prev = writeLocks.get(convKey) ?? Promise.resolve();
  const next = prev.catch(() => {}).then(fn);
  writeLocks.set(convKey, next);
  return next;
}

// --- Deep merge utility ---

function deepMerge(target: Record<string, unknown>, source: Record<string, unknown>): void {
  for (const key of Object.keys(source)) {
    const srcVal = source[key];
    const tgtVal = target[key];
    if (
      srcVal !== null &&
      typeof srcVal === "object" &&
      !Array.isArray(srcVal) &&
      tgtVal !== null &&
      typeof tgtVal === "object" &&
      !Array.isArray(tgtVal)
    ) {
      deepMerge(tgtVal as Record<string, unknown>, srcVal as Record<string, unknown>);
    } else {
      target[key] = srcVal;
    }
  }
}

// --- Core read/write (internal, must be called inside lock) ---

async function readConversationRaw(
  clientId: string,
  sessionId: string
): Promise<ConversationData | null> {
  const filePath = join(convDir(clientId, sessionId), "conversation.json");
  try {
    const content = await readFile(filePath, "utf-8");
    return JSON.parse(content) as ConversationData;
  } catch {
    return null;
  }
}

async function writeConversationRaw(data: ConversationData): Promise<void> {
  const dir = convDir(data.clientId, data.sessionId);
  await ensureDir(dir);
  data.updatedAt = new Date().toISOString();
  await writeFile(join(dir, "conversation.json"), JSON.stringify(data, null, 2));
}

// --- Public API (all use write lock) ---

export function initConversation(
  clientId: string,
  sessionId: string,
  language: string,
  deviceInfo: DeviceInfo
): Promise<ConversationData> {
  const key = `${clientId}/${sessionId}`;
  let result: ConversationData;
  const promise = withWriteLock(key, async () => {
    const existing = await readConversationRaw(clientId, sessionId);
    if (existing) {
      result = existing;
      return;
    }
    const now = new Date().toISOString();
    const data: ConversationData = {
      clientId,
      sessionId,
      language,
      deviceInfo,
      createdAt: now,
      updatedAt: now,
      turns: [],
    };
    await writeConversationRaw(data);
    result = data;
  });
  return promise.then(() => result!);
}

export interface StartTurnOpts {
  autoScreenshot?: boolean;
  audioReceivedAt?: string;
}

export function startTurn(
  clientId: string,
  sessionId: string,
  turnId: number,
  opts?: StartTurnOpts
): Promise<void> {
  const key = `${clientId}/${sessionId}`;
  return withWriteLock(key, async () => {
    const conv = await readConversationRaw(clientId, sessionId);
    if (!conv) return; // initConversation must be called first
    const turn: TurnData = {
      turnId,
      autoScreenshot: opts?.autoScreenshot,
      input: {},
      output: {},
      timing: {
        audioReceivedAt: opts?.audioReceivedAt ?? new Date().toISOString(),
      },
    };
    conv.turns.push(turn);
    await writeConversationRaw(conv);
  });
}

export function updateTurn(
  clientId: string,
  sessionId: string,
  turnId: number,
  updates: Record<string, unknown>
): Promise<void> {
  const key = `${clientId}/${sessionId}`;
  return withWriteLock(key, async () => {
    const conv = await readConversationRaw(clientId, sessionId);
    if (!conv) return;
    const turn = conv.turns.find((t) => t.turnId === turnId);
    if (!turn) return;
    deepMerge(turn as unknown as Record<string, unknown>, updates);
    await writeConversationRaw(conv);
  });
}

// --- Binary file saves (no lock needed, writes to separate files) ---

export async function saveAudioInput(
  clientId: string,
  sessionId: string,
  turnId: number,
  audioBuffer: Buffer
): Promise<string> {
  const dir = join(convDir(clientId, sessionId), "audio-input");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.wav`;
  await writeFile(join(dir, filename), audioBuffer);
  return `audio-input/${filename}`;
}

export async function saveScreenshot(
  clientId: string,
  sessionId: string,
  turnId: number,
  base64Data: string
): Promise<string> {
  const dir = join(convDir(clientId, sessionId), "screenshots");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.jpg`;
  await writeFile(join(dir, filename), Buffer.from(base64Data, "base64"));
  return `screenshots/${filename}`;
}

export async function saveUiTree(
  clientId: string,
  sessionId: string,
  turnId: number,
  uiTree: object
): Promise<string> {
  const dir = join(convDir(clientId, sessionId), "ui-trees");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.json`;
  await writeFile(join(dir, filename), JSON.stringify(uiTree, null, 2));
  return `ui-trees/${filename}`;
}

/** Save accumulated PCM chunks as a playable WAV file (16-bit LE, 24kHz mono). */
export async function saveAudioOutput(
  clientId: string,
  sessionId: string,
  turnId: number,
  pcmChunks: Buffer[]
): Promise<string> {
  const dir = join(convDir(clientId, sessionId), "audio-output");
  await ensureDir(dir);
  const pcm = Buffer.concat(pcmChunks);
  const sampleRate = 24000;
  const numChannels = 1;
  const bitsPerSample = 16;
  const byteRate = sampleRate * numChannels * (bitsPerSample / 8);
  const blockAlign = numChannels * (bitsPerSample / 8);
  const header = Buffer.alloc(44);
  header.write("RIFF", 0);
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8);
  header.write("fmt ", 12);
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20); // PCM format
  header.writeUInt16LE(numChannels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(bitsPerSample, 34);
  header.write("data", 36);
  header.writeUInt32LE(pcm.length, 40);
  const filename = `${turnId}_${Date.now()}.wav`;
  await writeFile(join(dir, filename), Buffer.concat([header, pcm]));
  return `audio-output/${filename}`;
}

// --- API helpers (read-only, no lock needed) ---

export async function listClients(): Promise<string[]> {
  try {
    const entries = await readdir(DATA_DIR, { withFileTypes: true });
    return entries
      .filter((e) => e.isDirectory())
      .map((e) => e.name)
      .sort();
  } catch {
    return [];
  }
}

export async function listConversations(
  clientId: string
): Promise<
  Array<{
    id: string;
    createdAt: string;
    updatedAt: string;
    language: string;
    turnCount: number;
    firstUserMessage?: string;
  }>
> {
  const clientDir = join(DATA_DIR, clientId);
  try {
    const entries = await readdir(clientDir, { withFileTypes: true });
    const convs = [];
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const conv = await readConversationRaw(clientId, entry.name);
      if (conv) {
        const firstTurn = conv.turns.find((t) => t.input.transcript);
        convs.push({
          id: conv.sessionId,
          createdAt: conv.createdAt,
          updatedAt: conv.updatedAt,
          language: conv.language,
          turnCount: conv.turns.length,
          firstUserMessage: firstTurn?.input.transcript,
        });
      }
    }
    return convs.sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
  } catch {
    return [];
  }
}

export async function getConversation(
  clientId: string,
  sessionId: string
): Promise<ConversationData | null> {
  return readConversationRaw(clientId, sessionId);
}

export async function deleteConversation(
  clientId: string,
  sessionId: string
): Promise<boolean> {
  const dir = convDir(clientId, sessionId);
  try {
    await rm(dir, { recursive: true, force: true });
    return true;
  } catch {
    return false;
  }
}

export function getDataDir(): string {
  return DATA_DIR;
}
