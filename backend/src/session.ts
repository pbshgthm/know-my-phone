import { randomUUID } from "crypto";

export interface ConversationEntry {
  role: "user" | "assistant";
  content: string;
  timestamp: number;
}

export interface Session {
  id: string;
  clientId: string;
  conversationId: string;
  conversationHistory: ConversationEntry[];
  createdAt: number;
  languageCode: string;
  autoScreenshot: boolean;
  turnCounter: number;
  currentTurnId: number;
  lastMessageTimestamp: number;
}

/** 30 minutes in milliseconds */
const INACTIVITY_THRESHOLD_MS = 30 * 60 * 1000;

/** Keep last 10 messages (≈5 turns) for LLM context */
const HISTORY_LIMIT = 10;

const sessions = new Map<string, Session>();

export function createSession(id: string, clientId: string = "unknown"): Session {
  const session: Session = {
    id,
    clientId,
    conversationId: id,
    conversationHistory: [],
    createdAt: Date.now(),
    languageCode: "en",
    autoScreenshot: false,
    turnCounter: 0,
    currentTurnId: 0,
    lastMessageTimestamp: 0,
  };
  sessions.set(id, session);
  return session;
}

export function getSession(id: string): Session | undefined {
  return sessions.get(id);
}

export function getOrCreateSession(id: string, clientId?: string): Session {
  const existing = sessions.get(id);
  if (existing) {
    if (clientId) existing.clientId = clientId;
    return existing;
  }
  return createSession(id, clientId);
}

export function deleteSession(id: string): void {
  sessions.delete(id);
}

export function addToHistory(
  session: Session,
  role: "user" | "assistant",
  content: string
): void {
  session.conversationHistory.push({
    role,
    content,
    timestamp: Date.now(),
  });

  // Keep last N messages (≈5 turns) to avoid context overflow
  if (session.conversationHistory.length > HISTORY_LIMIT) {
    session.conversationHistory = session.conversationHistory.slice(-HISTORY_LIMIT);
  }
}

export function getHistoryForLLM(
  session: Session
): Array<{ role: "user" | "assistant"; content: string }> {
  return session.conversationHistory.map(({ role, content }) => ({
    role,
    content,
  }));
}

/**
 * Check the 30-minute inactivity rule.
 * Returns true if a new conversation was started (history cleared).
 */
export function checkInactivityReset(session: Session): boolean {
  const now = Date.now();
  if (
    session.lastMessageTimestamp > 0 &&
    now - session.lastMessageTimestamp > INACTIVITY_THRESHOLD_MS
  ) {
    startNewConversation(session);
    console.log(
      `[${session.id}] ⏰ Inactivity > 30min, started new conversation: ${session.conversationId}`
    );
    return true;
  }
  return false;
}

/**
 * Start a new conversation within the same WebSocket session.
 * Generates a new conversationId, clears history, resets turn counter.
 */
export function startNewConversation(session: Session): void {
  session.conversationId = randomUUID();
  session.conversationHistory = [];
  session.turnCounter = 0;
}
