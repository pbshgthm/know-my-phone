import type WebSocket from "ws";
import type { RawData } from "ws";
import {
  createSession,
  deleteSession,
  getOrCreateSession,
  resetSession,
  type Session,
} from "./session.js";
import type { ScreenshotResponseMessage } from "./protocol.js";
import {
  handleAudioReceived,
  handleScreenshotResponse,
  handleScreenshotDeclined,
  cancelSession,
  cleanupSession,
} from "./stateMachine.js";
import { initConversation } from "./dataStore.js";

interface ClientState {
  session: Session;
  pendingAudioData: boolean;
  sessionId?: string;
}

const clients = new Map<WebSocket, ClientState>();
const SUPPORTED_LANGUAGES = new Set(["en", "ta", "hi", "kn", "te"]);

export function getAllClients(): Map<WebSocket, ClientState> {
  return clients;
}

export function handleConnection(ws: WebSocket): void {
  // Temporary session until hello message provides the client-generated sessionId
  const session = createSession(`tmp_${Date.now()}`);
  const state: ClientState = {
    session,
    pendingAudioData: false,
    sessionId: undefined,
  };
  clients.set(ws, state);

  console.log(`\n${'='.repeat(60)}`);
  console.log(`[WS] 🔌 Client connected`);
  console.log(`[WS] 👥 Active connections: ${clients.size}`);
  console.log(`${'='.repeat(60)}\n`);

  ws.on("message", (data: RawData, isBinary: boolean) => {
    handleMessage(ws, state, data, isBinary);
  });

  ws.on("close", () => {
    console.log(`\n[WS] 👋 Client disconnected, session: ${state.session.id}`);
    console.log(`[WS] 👥 Active connections: ${clients.size - 1}\n`);
    cleanupSession(state.session.id);
    // Always clean up session on disconnect (no persistent client IDs)
    deleteSession(state.session.id);
    clients.delete(ws);
  });

  ws.on("error", (err) => {
    console.error(`[WS] ❌ Error for session ${state.session.id}:`, err);
  });
}

function handleMessage(
  ws: WebSocket,
  state: ClientState,
  data: RawData,
  isBinary: boolean
): void {
  // Binary frame: this is audio data following an audio_data text message
  if (isBinary) {
    if (!state.pendingAudioData) {
      console.warn(`[WS] ⚠️  Received unexpected binary data for session ${state.session.id}`);
      ws.send(JSON.stringify({ type: "error", message: "Unexpected binary data" }));
      return;
    }
    state.pendingAudioData = false;

    const audioBuffer = Buffer.isBuffer(data)
      ? data
      : Buffer.from(data as ArrayBuffer);

    console.log(`[WS] 🎵 Session ${state.session.id}: received audio binary (${audioBuffer.length} bytes)`);

    // Fire and forget - the state machine handles the async flow
    // .catch() prevents unhandled promise rejection
    handleAudioReceived(ws, state.session, audioBuffer).catch((err) => {
      console.error(`[WS] ❌ Unhandled error in audio pipeline for session ${state.session.id}:`, err);
    });
    return;
  }

  // Text frame: parse JSON message
  let parsed: { type: string; [key: string]: unknown };
  try {
    parsed = JSON.parse(data.toString());
    console.log(`[WS] 📨 Session ${state.session.id}: received message type="${parsed.type}"`);
  } catch (err) {
    console.error(`[WS] ❌ Session ${state.session.id}: Invalid JSON:`, err);
    ws.send(JSON.stringify({ type: "error", message: "Invalid JSON" }));
    return;
  }

  switch (parsed.type) {
    case "hello": {
      const sessionId = typeof parsed.sessionId === "string" ? parsed.sessionId : "";
      if (!sessionId) {
        ws.send(JSON.stringify({ type: "error", message: "Missing sessionId" }));
        return;
      }
      const clientId = typeof parsed.clientId === "string" ? parsed.clientId : "unknown";
      // Bind to the client-provided sessionId
      if (state.sessionId !== sessionId) {
        // Clean up temporary session
        if (!state.sessionId) {
          cleanupSession(state.session.id);
          deleteSession(state.session.id);
        }
        state.sessionId = sessionId;
        state.session = getOrCreateSession(sessionId, clientId);
        console.log(`[WS] 🔑 Bound to clientId=${clientId}, sessionId=${sessionId}`);
      }
      // Initialize conversation data on disk
      initConversation(clientId, sessionId, state.session.languageCode).catch((err) => {
        console.error(`[WS] ❌ Failed to init conversation on disk:`, err);
      });
      break;
    }

    case "reset_session": {
      if (!state.sessionId) {
        ws.send(JSON.stringify({ type: "error", message: "No session; send hello first" }));
        return;
      }
      cleanupSession(state.session.id);
      state.session = resetSession(state.sessionId, state.session.languageCode);
      console.log(`[WS] 🔄 Session reset: ${state.sessionId}`);
      break;
    }

    case "set_language": {
      const raw = typeof parsed.languageCode === "string" ? parsed.languageCode : "en";
      const normalized = raw.trim().toLowerCase();
      state.session.languageCode = SUPPORTED_LANGUAGES.has(normalized) ? normalized : "en";
      console.log(`[WS] 🌐 Session ${state.session.id}: language=${state.session.languageCode}`);
      break;
    }

    case "audio_data":
      // Next binary frame will contain the audio
      state.pendingAudioData = true;
      console.log(`[WS] 🎤 Session ${state.session.id}: expecting audio data (${parsed.format}, ${parsed.sampleRate}Hz)`);
      break;

    case "cancel":
      console.log(`[WS] 🚫 Session ${state.session.id}: cancel requested`);
      cancelSession(ws, state.session.id);
      break;

    case "screenshot_response":
      console.log(`[WS] 📸 Session ${state.session.id}: received screenshot`);
      handleScreenshotResponse(ws, state.session, parsed as unknown as ScreenshotResponseMessage).catch((err) => {
        console.error(`[WS] ❌ Unhandled error in screenshot response for session ${state.session.id}:`, err);
      });
      break;

    case "screenshot_declined":
      console.log(`[WS] 🚫 Session ${state.session.id}: screenshot declined`);
      handleScreenshotDeclined(ws, state.session).catch((err) => {
        console.error(`[WS] ❌ Unhandled error in screenshot declined for session ${state.session.id}:`, err);
      });
      break;

    case "set_auto_screenshot": {
      state.session.autoScreenshot = typeof parsed.enabled === "boolean" ? parsed.enabled : false;
      console.log(`[WS] 📸 Session ${state.session.id}: autoScreenshot=${state.session.autoScreenshot}`);
      break;
    }

    default:
      console.warn(`[WS] ⚠️  Session ${state.session.id}: Unknown message type: ${parsed.type}`);
      ws.send(
        JSON.stringify({
          type: "error",
          message: `Unknown message type: ${parsed.type}`,
        })
      );
  }
}
