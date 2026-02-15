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

export interface DeviceInfo {
  manufacturer: string;
  model: string;
  androidVersion: string;
}

export interface HelloMessage {
  type: "hello";
  sessionId: string;
  clientId?: string;
  deviceInfo: DeviceInfo;
}

export interface SetLanguageMessage {
  type: "set_language";
  languageCode: string;
}

export interface RedactionInfo {
  type: string;
  count: number;
  nodeIds: string[];
}

export interface VisualRedactionInfo {
  type: string;       // e.g. "FACE", "QR_CODE", "UPI_ID"
  label: string;      // human-readable label
  bounds: {
    left: number;
    top: number;
    right: number;
    bottom: number;
  };
}

export interface ScreenshotResponseMessage {
  type: "screenshot_response";
  screenshot: string; // base64
  uiTree: UiTree;
  redacted?: boolean;
  redactions?: RedactionInfo[];
  visualRedactions?: VisualRedactionInfo[];
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

export interface ErrorMessage {
  type: "error";
  message: string;
}

export interface CancelledMessage {
  type: "cancelled";
}

export interface AnswerStartMessage {
  type: "answer_start";
}

export interface HighlightsMessage {
  type: "highlights";
  highlights: Highlight[];
}

export interface AnswerEndMessage {
  type: "answer_end";
  text: string;
  highlights: Highlight[];
  hasNextStep?: boolean;
  confirmLabel?: string;
}

export type ServerMessage =
  | TranscriptMessage
  | ScreenshotRequestMessage
  | AnswerStartMessage
  | HighlightsMessage
  | AnswerEndMessage
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
  spokenRequest?: string;
  confirmLabel?: string;
}

// Visual analysis result from LLM

export interface AnalysisResult {
  answer: string;
  highlights: Highlight[];
  nextStep?: boolean;
  confirmLabel?: string;
}
