import { describe, it, expect } from '@jest/globals';
import type {
  AudioDataMessage,
  ScreenshotResponseMessage,
  TranscriptMessage,
  NeedScreenshotMessage,
  AnswerMessage,
  ErrorMessage,
  Highlight,
  UiTree,
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

    const needScreenshot: NeedScreenshotMessage = {
      type: 'need_screenshot',
      reason: 'Need to see image',
    };
    expect(needScreenshot.type).toBe('need_screenshot');

    const highlight: Highlight = {
      elementId: 'n_1',
      label: 'Tap here',
      bounds: { left: 0, top: 0, right: 100, bottom: 50 },
    };

    const answer: AnswerMessage = {
      type: 'answer',
      text: 'Tap the button',
      highlights: [highlight],
      hasAudio: true,
    };
    expect(answer.highlights.length).toBe(1);

    const error: ErrorMessage = {
      type: 'error',
      message: 'Something went wrong',
    };
    expect(error.type).toBe('error');
  });
});
