import { describe, it, expect } from '@jest/globals';
import {
  createSession,
  getSession,
  deleteSession,
  addToHistory,
  getHistoryForLLM,
  formatTimeAgo,
} from '../session.js';

describe('Session Management', () => {
  it('should create a new session', () => {
    const session = createSession(`test_${Date.now()}_${Math.random()}`);
    expect(session.id).toBeDefined();
    expect(session.conversationHistory).toEqual([]);
    expect(session.createdAt).toBeLessThanOrEqual(Date.now());
  });

  it('should add messages to history', () => {
    const session = createSession(`test_${Date.now()}_${Math.random()}`);
    addToHistory(session, 'user', 'Hello');
    addToHistory(session, 'assistant', 'Hi there!');

    expect(session.conversationHistory.length).toBe(2);
    expect(session.conversationHistory[0].role).toBe('user');
    expect(session.conversationHistory[0].content).toBe('Hello');
    expect(session.conversationHistory[1].role).toBe('assistant');
  });

  it('should cap history at 10 messages', () => {
    const session = createSession(`test_${Date.now()}_${Math.random()}`);

    // Add 15 messages
    for (let i = 0; i < 15; i++) {
      addToHistory(session, 'user', `Message ${i}`);
    }

    expect(session.conversationHistory.length).toBe(10);
    // Should keep the last 10 messages
    expect(session.conversationHistory[0].content).toBe('Message 5');
    expect(session.conversationHistory[9].content).toBe('Message 14');
  });

  it('should format history for LLM with timestamp prefixes on user messages', () => {
    const session = createSession(`test_${Date.now()}_${Math.random()}`);
    addToHistory(session, 'user', 'What is this?');
    addToHistory(session, 'assistant', 'This is a button');

    const history = getHistoryForLLM(session);
    expect(history.length).toBe(2);
    // User messages should be prefixed with a timestamp like "[just now] What is this?"
    expect(history[0].role).toBe('user');
    expect(history[0].content).toMatch(/^\[.+\] What is this\?$/);
    // Assistant messages should NOT be prefixed
    expect(history[1]).toEqual({ role: 'assistant', content: 'This is a button' });
  });

  it('should retrieve and delete sessions', () => {
    const session = createSession(`test_${Date.now()}_${Math.random()}`);
    const id = session.id;

    const retrieved = getSession(id);
    expect(retrieved).toBe(session);

    deleteSession(id);
    const notFound = getSession(id);
    expect(notFound).toBeUndefined();
  });
});

describe('formatTimeAgo', () => {
  it('should return "just now" for < 60 seconds', () => {
    expect(formatTimeAgo(0)).toBe('just now');
    expect(formatTimeAgo(30_000)).toBe('just now');
    expect(formatTimeAgo(59_999)).toBe('just now');
  });

  it('should return minutes for 1-59 minutes', () => {
    expect(formatTimeAgo(60_000)).toBe('1 min ago');
    expect(formatTimeAgo(5 * 60_000)).toBe('5 min ago');
    expect(formatTimeAgo(59 * 60_000)).toBe('59 min ago');
  });

  it('should return hours for >= 60 minutes', () => {
    expect(formatTimeAgo(60 * 60_000)).toBe('1 hr ago');
    expect(formatTimeAgo(3 * 60 * 60_000)).toBe('3 hr ago');
  });
});
