import { mkdir, writeFile, readFile, readdir, rm } from "fs/promises";
import { join } from "path";
import type { Highlight } from "./protocol.js";

// Data directory at project root (server runs from backend/)
const DATA_DIR = process.env.DATA_DIR || join(process.cwd(), "..", "data");

export interface TurnData {
  turnId: number;
  timestamp: number;
  autoScreenshot?: boolean;

  // Pipeline timing (ms timestamps)
  audioReceivedAt?: number;
  sttStartedAt?: number;
  sttCompletedAt?: number;
  triageStartedAt?: number;
  triageCompletedAt?: number;
  screenshotRequestedAt?: number;
  screenshotReceivedAt?: number;
  llmStartedAt?: number;
  llmFirstTokenAt?: number;
  llmDoneAt?: number;
  firstAudioSentAt?: number;
  sentenceCount?: number;
  completedAt?: number;

  // Data
  userTranscript?: string;
  userAudioFile?: string;
  screenshotFile?: string;
  uiTreeFile?: string;
  assistantText?: string;
  assistantAudioFile?: string;
  highlights?: Highlight[];
  triageResult?: { needsScreenshot: boolean; reason: string };
}

export interface ConversationData {
  clientId: string;
  conversationId: string;
  language: string;
  createdAt: number;
  updatedAt: number;
  turns: TurnData[];
}

function convDir(clientId: string, conversationId: string): string {
  return join(DATA_DIR, clientId, conversationId);
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

// --- Core read/write (internal, must be called inside lock) ---

async function readConversationRaw(
  clientId: string,
  conversationId: string
): Promise<ConversationData | null> {
  const filePath = join(convDir(clientId, conversationId), "conversation.json");
  try {
    const content = await readFile(filePath, "utf-8");
    return JSON.parse(content) as ConversationData;
  } catch {
    return null;
  }
}

async function writeConversationRaw(data: ConversationData): Promise<void> {
  const dir = convDir(data.clientId, data.conversationId);
  await ensureDir(dir);
  data.updatedAt = Date.now();
  await writeFile(join(dir, "conversation.json"), JSON.stringify(data, null, 2));
}

// --- Public API (all use write lock) ---

export function initConversation(
  clientId: string,
  conversationId: string,
  language: string
): Promise<ConversationData> {
  const key = `${clientId}/${conversationId}`;
  // initConversation returns data, so we wrap differently
  let result: ConversationData;
  const promise = withWriteLock(key, async () => {
    const existing = await readConversationRaw(clientId, conversationId);
    if (existing) {
      result = existing;
      return;
    }
    const data: ConversationData = {
      clientId,
      conversationId,
      language,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      turns: [],
    };
    await writeConversationRaw(data);
    result = data;
  });
  return promise.then(() => result!);
}

export interface StartTurnOpts {
  autoScreenshot?: boolean;
}

export function startTurn(
  clientId: string,
  conversationId: string,
  turnId: number,
  opts?: StartTurnOpts
): Promise<void> {
  const key = `${clientId}/${conversationId}`;
  return withWriteLock(key, async () => {
    let conv = await readConversationRaw(clientId, conversationId);
    if (!conv) {
      conv = {
        clientId,
        conversationId,
        language: "en",
        createdAt: Date.now(),
        updatedAt: Date.now(),
        turns: [],
      };
    }
    const now = Date.now();
    const turn: TurnData = {
      turnId,
      timestamp: now,
      audioReceivedAt: now,
      autoScreenshot: opts?.autoScreenshot,
    };
    conv.turns.push(turn);
    await writeConversationRaw(conv);
  });
}

export function updateTurn(
  clientId: string,
  conversationId: string,
  turnId: number,
  updates: Partial<TurnData>
): Promise<void> {
  const key = `${clientId}/${conversationId}`;
  return withWriteLock(key, async () => {
    const conv = await readConversationRaw(clientId, conversationId);
    if (!conv) return;
    const turn = conv.turns.find((t) => t.turnId === turnId);
    if (!turn) return;
    Object.assign(turn, updates);
    await writeConversationRaw(conv);
  });
}

// --- Binary file saves (no lock needed, writes to separate files) ---

export async function saveAudioInput(
  clientId: string,
  conversationId: string,
  turnId: number,
  audioBuffer: Buffer
): Promise<string> {
  const dir = join(convDir(clientId, conversationId), "audio-input");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.wav`;
  await writeFile(join(dir, filename), audioBuffer);
  return `audio-input/${filename}`;
}

export async function saveScreenshot(
  clientId: string,
  conversationId: string,
  turnId: number,
  base64Data: string
): Promise<string> {
  const dir = join(convDir(clientId, conversationId), "screenshots");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.jpg`;
  await writeFile(join(dir, filename), Buffer.from(base64Data, "base64"));
  return `screenshots/${filename}`;
}

export async function saveUiTree(
  clientId: string,
  conversationId: string,
  turnId: number,
  uiTree: object
): Promise<string> {
  const dir = join(convDir(clientId, conversationId), "ui-trees");
  await ensureDir(dir);
  const filename = `${turnId}_${Date.now()}.json`;
  await writeFile(join(dir, filename), JSON.stringify(uiTree, null, 2));
  return `ui-trees/${filename}`;
}

/** Save accumulated PCM chunks as a playable WAV file (16-bit LE, 24kHz mono). */
export async function saveAudioOutput(
  clientId: string,
  conversationId: string,
  turnId: number,
  pcmChunks: Buffer[]
): Promise<string> {
  const dir = join(convDir(clientId, conversationId), "audio-output");
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
    createdAt: number;
    updatedAt: number;
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
        const firstTurn = conv.turns.find((t) => t.userTranscript);
        convs.push({
          id: conv.conversationId,
          createdAt: conv.createdAt,
          updatedAt: conv.updatedAt,
          language: conv.language,
          turnCount: conv.turns.length,
          firstUserMessage: firstTurn?.userTranscript,
        });
      }
    }
    return convs.sort((a, b) => b.updatedAt - a.updatedAt);
  } catch {
    return [];
  }
}

export async function getConversation(
  clientId: string,
  conversationId: string
): Promise<ConversationData | null> {
  return readConversationRaw(clientId, conversationId);
}

export async function deleteConversation(
  clientId: string,
  conversationId: string
): Promise<boolean> {
  const dir = convDir(clientId, conversationId);
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
