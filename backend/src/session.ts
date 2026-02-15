import { v4 as uuidv4 } from "uuid";

export interface ConversationEntry {
  role: "user" | "assistant";
  content: string;
  timestamp: number;
}

export interface Session {
  id: string;
  clientId?: string;
  conversationHistory: ConversationEntry[];
  createdAt: number;
  /** Pending audio buffer waiting after an audio_data text message */
  pendingAudioData: boolean;
  userCount: number;
  assistantCount: number;
  languageCode: string;
}

const sessions = new Map<string, Session>();
const sessionsByClientId = new Map<string, Session>();

export function createSession(): Session {
  const session: Session = {
    id: uuidv4(),
    clientId: undefined,
    conversationHistory: [],
    createdAt: Date.now(),
    pendingAudioData: false,
    userCount: 0,
    assistantCount: 0,
    languageCode: "en",
  };
  sessions.set(session.id, session);
  return session;
}

export function getSession(id: string): Session | undefined {
  return sessions.get(id);
}

export function getOrCreateSessionForClient(clientId: string): Session {
  const existing = sessionsByClientId.get(clientId);
  if (existing) return existing;
  const session = createSession();
  session.clientId = clientId;
  sessionsByClientId.set(clientId, session);
  return session;
}

export function resetSessionForClient(
  clientId: string,
  languageCode?: string
): Session {
  const existing = sessionsByClientId.get(clientId);
  if (existing) {
    sessions.delete(existing.id);
  }
  const session = createSession();
  session.clientId = clientId;
  if (languageCode) {
    session.languageCode = languageCode;
  }
  sessionsByClientId.set(clientId, session);
  return session;
}

export function deleteSession(id: string): void {
  const session = sessions.get(id);
  if (session?.clientId) {
    const current = sessionsByClientId.get(session.clientId);
    if (current?.id === session.id) {
      sessionsByClientId.delete(session.clientId);
    }
  }
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

  if (role === "user") {
    session.userCount += 1;
  } else {
    session.assistantCount += 1;
  }

  // Keep last 20 messages to avoid context overflow
  if (session.conversationHistory.length > 20) {
    session.conversationHistory = session.conversationHistory.slice(-20);
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

export function getMessageCounts(session: Session): { userCount: number; assistantCount: number } {
  return { userCount: session.userCount, assistantCount: session.assistantCount };
}
