export interface ConversationEntry {
  role: "user" | "assistant";
  content: string;
  timestamp: number;
}

export interface Session {
  id: string;
  conversationHistory: ConversationEntry[];
  createdAt: number;
  languageCode: string;
  autoScreenshot: boolean;
}

const sessions = new Map<string, Session>();

export function createSession(id: string): Session {
  const session: Session = {
    id,
    conversationHistory: [],
    createdAt: Date.now(),
    languageCode: "en",
    autoScreenshot: false,
  };
  sessions.set(id, session);
  return session;
}

export function getSession(id: string): Session | undefined {
  return sessions.get(id);
}

export function getOrCreateSession(id: string): Session {
  const existing = sessions.get(id);
  if (existing) return existing;
  return createSession(id);
}

export function resetSession(id: string, languageCode?: string): Session {
  const existing = sessions.get(id);
  if (existing) {
    sessions.delete(id);
  }
  const session = createSession(id);
  if (languageCode) {
    session.languageCode = languageCode;
  }
  return session;
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
