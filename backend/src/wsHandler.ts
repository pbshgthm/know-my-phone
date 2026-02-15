import type WebSocket from "ws";
import type { RawData } from "ws";
import { createSession, deleteSession, type Session } from "./session.js";
import type { ScreenshotResponseMessage } from "./protocol.js";
import {
  handleAudioReceived,
  handleScreenshotResponse,
  handleScreenshotDeclined,
  cancelSession,
  cleanupSession,
} from "./stateMachine.js";

interface ClientState {
  session: Session;
  pendingAudioData: boolean;
}

const clients = new Map<WebSocket, ClientState>();

export function getAllClients(): Map<WebSocket, ClientState> {
  return clients;
}

export function handleConnection(ws: WebSocket): void {
  const session = createSession();
  const state: ClientState = {
    session,
    pendingAudioData: false,
  };
  clients.set(ws, state);

  console.log(`\n${'='.repeat(60)}`);
  console.log(`[WS] 🔌 Client connected`);
  console.log(`[WS] 📋 Session ID: ${session.id}`);
  console.log(`[WS] 👥 Active connections: ${clients.size}`);
  console.log(`${'='.repeat(60)}\n`);

  ws.on("message", (data: RawData, isBinary: boolean) => {
    handleMessage(ws, state, data, isBinary);
  });

  ws.on("close", () => {
    console.log(`\n[WS] 👋 Client disconnected, session: ${session.id}`);
    console.log(`[WS] 👥 Active connections: ${clients.size - 1}\n`);
    cleanupSession(session.id);
    deleteSession(session.id);
    clients.delete(ws);
  });

  ws.on("error", (err) => {
    console.error(`[WS] ❌ Error for session ${session.id}:`, err);
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
