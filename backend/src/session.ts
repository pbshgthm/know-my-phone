import { v4 as uuidv4 } from "uuid";

export interface ConversationEntry {
  role: "user" | "assistant";
  content: string;
  timestamp: number;
}

export interface Session {
  id: string;
  conversationHistory: ConversationEntry[];
  createdAt: number;
  /** Pending audio buffer waiting after an audio_data text message */
  pendingAudioData: boolean;
}

const sessions = new Map<string, Session>();

export function createSession(): Session {
  const session: Session = {
    id: uuidv4(),
    conversationHistory: [],
    createdAt: Date.now(),
    pendingAudioData: false,
  };
  sessions.set(session.id, session);
  return session;
}

export function getSession(id: string): Session | undefined {
  return sessions.get(id);
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
