import { describe, it, expect } from '@jest/globals';
import type {
  AudioDataMessage,
  ScreenshotResponseMessage,
  TranscriptMessage,
  AnswerStartMessage,
  AnswerEndMessage,
  ErrorMessage,
  Highlight,
  UiTree,
  RedactionInfo,
} from '../protocol.js';

describe('Protocol Message Types', () => {
  it('should have correct structure for AudioDataMessage', () => {
    const msg: AudioDataMessage = {
      type: 'audio_data',
      format: 'wav',
      sampleRate: 16000,
    };
    expect(msg.type).toBe('audio_data');
    expect(msg.sampleRate).toBe(16000);
  });

  it('should have correct structure for ScreenshotResponseMessage', () => {
    const uiTree: UiTree = {
      screen: { packageName: 'com.test', timestamp: 123456 },
      nodes: [
        {
          id: 'n_1',
          text: 'Button',
          contentDescription: null,
          className: 'Button',
          clickable: true,
          enabled: true,
          bounds: { left: 0, top: 0, right: 100, bottom: 50 },
        },
      ],
    };

    const msg: ScreenshotResponseMessage = {
      type: 'screenshot_response',
      screenshot: 'base64string',
      uiTree,
    };
    expect(msg.type).toBe('screenshot_response');
    expect(msg.uiTree.nodes.length).toBe(1);
  });

  it('should have correct structure for server messages', () => {
    const transcript: TranscriptMessage = {
      type: 'transcript',
      text: 'Hello world',
    };
    expect(transcript.type).toBe('transcript');

    // Highlight with optional bounds
    const highlightWithBounds: Highlight = {
      elementId: 'n_1',
      label: 'Tap here',
      bounds: { left: 0, top: 0, right: 100, bottom: 50 },
    };

    const highlightWithoutBounds: Highlight = {
      elementId: 'n_2',
      label: 'Settings',
    };

    const answerStart: AnswerStartMessage = {
      type: 'answer_start',
    };
    expect(answerStart.type).toBe('answer_start');

    const answerEnd: AnswerEndMessage = {
      type: 'answer_end',
      text: 'Tap the button',
      highlights: [highlightWithBounds, highlightWithoutBounds],
    };
    expect(answerEnd.highlights.length).toBe(2);
    expect(answerEnd.highlights[0].bounds).toBeDefined();
    expect(answerEnd.highlights[1].bounds).toBeUndefined();

    const error: ErrorMessage = {
      type: 'error',
      message: 'Something went wrong',
    };
    expect(error.type).toBe('error');
  });

  it('should support ScreenshotResponseMessage with redaction fields', () => {
    const uiTree: UiTree = {
      screen: { packageName: 'com.test', timestamp: 123456 },
      nodes: [
        {
          id: 'n_1',
          text: '[REDACTED:PHONE_NUMBER]',
          contentDescription: null,
          className: 'TextView',
          clickable: false,
          enabled: true,
          bounds: { left: 0, top: 0, right: 200, bottom: 50 },
        },
      ],
    };

    const redactions: RedactionInfo[] = [
      { type: '[REDACTED:PHONE_NUMBER]', count: 1, nodeIds: ['n_1'] },
    ];

    const msg: ScreenshotResponseMessage = {
      type: 'screenshot_response',
      screenshot: 'base64string',
      uiTree,
      redacted: true,
      redactions,
    };
    expect(msg.type).toBe('screenshot_response');
    expect(msg.redacted).toBe(true);
    expect(msg.redactions).toHaveLength(1);
    expect(msg.redactions![0].type).toBe('[REDACTED:PHONE_NUMBER]');
    expect(msg.redactions![0].count).toBe(1);
    expect(msg.redactions![0].nodeIds).toEqual(['n_1']);
  });

  it('should support ScreenshotResponseMessage without redaction fields (backward compat)', () => {
    const uiTree: UiTree = {
      screen: { packageName: 'com.test', timestamp: 123456 },
      nodes: [],
    };

    const msg: ScreenshotResponseMessage = {
      type: 'screenshot_response',
      screenshot: 'base64string',
      uiTree,
    };
    expect(msg.type).toBe('screenshot_response');
    expect(msg.redacted).toBeUndefined();
    expect(msg.redactions).toBeUndefined();
  });
});
