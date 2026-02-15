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

export interface HelloMessage {
  type: "hello";
  clientId: string;
}

export interface ResetSessionMessage {
  type: "reset_session";
}

export interface SetLanguageMessage {
  type: "set_language";
  languageCode: string;
}

export interface ScreenshotResponseMessage {
  type: "screenshot_response";
  screenshot: string; // base64
  uiTree: UiTree;
}

export interface ScreenshotDeclinedMessage {
  type: "screenshot_declined";
}

export interface CancelMessage {
  type: "cancel";
}

export type ClientMessage =
  | AudioDataMessage
  | HelloMessage
  | ResetSessionMessage
  | SetLanguageMessage
  | ScreenshotResponseMessage
  | ScreenshotDeclinedMessage
  | CancelMessage;

// Server -> Client messages

export interface TranscriptMessage {
  type: "transcript";
  text: string;
}

export interface NeedScreenshotMessage {
  type: "need_screenshot";
  reason: string;
}

export interface ScreenshotRequestMessage {
  type: "screenshot_request";
  text: string;
  reason: string;
  hasAudio: boolean;
}

export interface AnswerMessage {
  type: "answer";
  text: string;
  highlights: Highlight[];
  hasAudio: boolean;
}

export interface SessionStatusMessage {
  type: "session_status";
  sessionId: string;
  userCount: number;
  assistantCount: number;
}

export interface ErrorMessage {
  type: "error";
  message: string;
}

export interface CancelledMessage {
  type: "cancelled";
}

export type ServerMessage =
  | TranscriptMessage
  | NeedScreenshotMessage
  | ScreenshotRequestMessage
  | AnswerMessage
  | SessionStatusMessage
  | ErrorMessage
  | CancelledMessage;

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
  requestSpeech: string;
}

// Visual analysis result from LLM

export interface AnalysisResult {
  answer: string;
  highlights: Highlight[];
}
