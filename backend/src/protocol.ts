// WebSocket protocol message types

export interface Highlight {
  elementId: string;
  label: string;
  bounds: {
    left: number;
    top: number;
    right: number;
    bottom: number;
  };
}

// Client -> Server messages

export interface AudioDataMessage {
  type: "audio_data";
  format: "wav";
  sampleRate: number;
}

export interface ScreenshotResponseMessage {
  type: "screenshot_response";
  screenshot: string; // base64
  uiTree: UiTree;
}

export interface ScreenshotDeclinedMessage {
  type: "screenshot_declined";
}

export type ClientMessage =
  | AudioDataMessage
  | ScreenshotResponseMessage
  | ScreenshotDeclinedMessage;

// Server -> Client messages

export interface TranscriptMessage {
  type: "transcript";
  text: string;
}

export interface NeedScreenshotMessage {
  type: "need_screenshot";
  reason: string;
}

export interface AnswerMessage {
  type: "answer";
  text: string;
  highlights: Highlight[];
}

export interface ErrorMessage {
  type: "error";
  message: string;
}

export type ServerMessage =
  | TranscriptMessage
  | NeedScreenshotMessage
  | AnswerMessage
  | ErrorMessage;

// UI tree structure from Android accessibility service

export interface UiNode {
  id: string;
  text: string | null;
  contentDescription: string | null;
  className: string;
  clickable: boolean;
  enabled: boolean;
  bounds: {
    left: number;
    top: number;
    right: number;
    bottom: number;
  };
}

export interface UiTree {
  screen: {
    packageName: string;
    timestamp: number;
  };
  nodes: UiNode[];
}

// Triage result from LLM

export interface TriageResult {
  needsScreenshot: boolean;
  reason: string;
}

// Visual analysis result from LLM

export interface AnalysisResult {
  answer: string;
  highlights: Highlight[];
}
