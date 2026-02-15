// WebSocket protocol message types

export interface Highlight {
  elementId: string;
  label: string;
  bounds?: {
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
  sessionId: string;
  clientId?: string;
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

export interface SetAutoScreenshotMessage {
  type: "set_auto_screenshot";
  enabled: boolean;
}

export type ClientMessage =
  | AudioDataMessage
  | HelloMessage
  | ResetSessionMessage
  | SetLanguageMessage
  | ScreenshotResponseMessage
  | ScreenshotDeclinedMessage
  | CancelMessage
  | SetAutoScreenshotMessage;

// Server -> Client messages

export interface TranscriptMessage {
  type: "transcript";
  text: string;
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

export interface ErrorMessage {
  type: "error";
  message: string;
}

export interface CancelledMessage {
  type: "cancelled";
}

export type ServerMessage =
  | TranscriptMessage
  | ScreenshotRequestMessage
  | AnswerMessage
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
}

// Visual analysis result from LLM

export interface AnalysisResult {
  answer: string;
  highlights: Highlight[];
}
