import { describe, it, expect } from '@jest/globals';
import {
  createSession,
  getSession,
  deleteSession,
  addToHistory,
  getHistoryForLLM,
} from '../session.js';

describe('Session Management', () => {
  it('should create a new session', () => {
    const session = createSession();
    expect(session.id).toBeDefined();
    expect(session.conversationHistory).toEqual([]);
    expect(session.createdAt).toBeLessThanOrEqual(Date.now());
  });

  it('should add messages to history', () => {
    const session = createSession();
    addToHistory(session, 'user', 'Hello');
    addToHistory(session, 'assistant', 'Hi there!');

    expect(session.conversationHistory.length).toBe(2);
    expect(session.conversationHistory[0].role).toBe('user');
    expect(session.conversationHistory[0].content).toBe('Hello');
    expect(session.conversationHistory[1].role).toBe('assistant');
  });

  it('should cap history at 20 messages', () => {
    const session = createSession();

    // Add 25 messages
    for (let i = 0; i < 25; i++) {
      addToHistory(session, 'user', `Message ${i}`);
    }

    expect(session.conversationHistory.length).toBe(20);
    // Should keep the last 20 messages
    expect(session.conversationHistory[0].content).toBe('Message 5');
    expect(session.conversationHistory[19].content).toBe('Message 24');
  });

  it('should format history for LLM', () => {
    const session = createSession();
    addToHistory(session, 'user', 'What is this?');
    addToHistory(session, 'assistant', 'This is a button');

    const history = getHistoryForLLM(session);
    expect(history.length).toBe(2);
    expect(history[0]).toEqual({ role: 'user', content: 'What is this?' });
    expect(history[1]).toEqual({ role: 'assistant', content: 'This is a button' });
    // Should not include timestamp
    expect((history[0] as any).timestamp).toBeUndefined();
  });

  it('should retrieve and delete sessions', () => {
    const session = createSession();
    const id = session.id;

    const retrieved = getSession(id);
    expect(retrieved).toBe(session);

    deleteSession(id);
    const notFound = getSession(id);
    expect(notFound).toBeUndefined();
  });
});
