import type WebSocket from "ws";
import type { Session } from "./session.js";
import type {
  AnswerMessage,
  ScreenshotResponseMessage,
  AnalysisResult,
  UiTree,
} from "./protocol.js";
import { addToHistory, getHistoryForLLM } from "./session.js";
import { transcribeAudio } from "./services/whisper.js";
import { triageQuery, visualAnalysis, textAnalysis } from "./services/openrouter.js";
import { textToSpeech } from "./services/elevenlabs.js";

function sendJSON(ws: WebSocket, data: object): void {
  if (ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(data));
  }
}

function sendBinary(ws: WebSocket, data: Buffer): void {
  if (ws.readyState === ws.OPEN) {
    ws.send(data);
  }
}

function sendError(ws: WebSocket, message: string): void {
  sendJSON(ws, { type: "error", message });
}

interface PendingScreenshotRequest {
  userText: string;
  reason: string;
  timer: ReturnType<typeof setTimeout>;
}

const pendingScreenshots = new Map<string, PendingScreenshotRequest>();

// Per-session AbortController for cancelling in-flight API requests
const sessionAbortControllers = new Map<string, AbortController>();

export function hasPendingScreenshot(sessionId: string): boolean {
  return pendingScreenshots.has(sessionId);
}

/**
 * Get or create an AbortController for a session.
 * Aborts the previous controller if one exists.
 */
export function resetSessionAbort(sessionId: string): AbortController {
  const existing = sessionAbortControllers.get(sessionId);
  if (existing) {
    existing.abort();
  }
  const controller = new AbortController();
  sessionAbortControllers.set(sessionId, controller);
  return controller;
}

/**
 * Cancel any in-flight work for a session.
 */
export function cancelSession(ws: WebSocket, sessionId: string): void {
  const controller = sessionAbortControllers.get(sessionId);
  if (controller) {
    controller.abort();
    sessionAbortControllers.delete(sessionId);
  }
  // Clean up pending screenshot
  const pending = pendingScreenshots.get(sessionId);
  if (pending) {
    clearTimeout(pending.timer);
    pendingScreenshots.delete(sessionId);
  }
  sendJSON(ws, { type: "cancelled" });
  console.log(`[${sessionId}] 🚫 Session cancelled`);
}

export async function handleAudioReceived(
  ws: WebSocket,
  session: Session,
  audioBuffer: Buffer
): Promise<void> {
  // Create a fresh AbortController for this request (aborts any previous)
  const controller = resetSessionAbort(session.id);
  const signal = controller.signal;

  console.log(`\n${'='.repeat(60)}`);
  console.log(`[${session.id}] 🎬 Starting audio processing pipeline`);
  console.log(`${'='.repeat(60)}\n`);

  try {
    // Step 1: STT
    console.log(`[${session.id}] 📝 Step 1/4: Transcribing audio (${audioBuffer.length} bytes)...`);
    const startSTT = Date.now();
    const transcript = await transcribeAudio(audioBuffer, signal);
    const sttTime = Date.now() - startSTT;
    console.log(`[${session.id}] ✅ STT complete (${sttTime}ms)`);
    console.log(`[${session.id}] 💬 Transcript: "${transcript}"`);

    if (!transcript || transcript.trim().length === 0) {
      console.warn(`[${session.id}] ⚠️  Empty transcript, sending error to client`);
      sendError(ws, "Could not understand audio. Please try again.");
      return;
    }

    // Send transcript back to client
    console.log(`[${session.id}] 📤 Sending transcript to client`);
    sendJSON(ws, { type: "transcript", text: transcript });

    // Add user message to history
    addToHistory(session, "user", transcript);
    console.log(`[${session.id}] 📚 Added to history (total: ${session.conversationHistory.length} messages)`);

    // Step 2: Triage - does this need a screenshot?
    console.log(`[${session.id}] 🤔 Step 2/4: Running triage query...`);
    const startTriage = Date.now();
    const triage = await triageQuery(transcript, getHistoryForLLM(session), signal);
    const triageTime = Date.now() - startTriage;
    console.log(`[${session.id}] ✅ Triage complete (${triageTime}ms)`);
    console.log(`[${session.id}] 🔍 Triage result: needsScreenshot=${triage.needsScreenshot}, reason="${triage.reason}"`);

    if (triage.needsScreenshot) {
      // Store pending request with TTL and ask client for screenshot
      const timer = setTimeout(() => {
        if (pendingScreenshots.has(session.id)) {
          pendingScreenshots.delete(session.id);
          console.warn(`[${session.id}] ⏰ Screenshot request timed out after 60s`);
          sendError(ws, "Screenshot request timed out.");
        }
      }, 60_000);

      pendingScreenshots.set(session.id, {
        userText: transcript,
        reason: triage.reason,
        timer,
      });
      console.log(`[${session.id}] 📸 Requesting screenshot from client`);
      sendJSON(ws, {
        type: "need_screenshot",
        reason: triage.reason,
      });
      console.log(`[${session.id}] ⏸️  Waiting for screenshot response...\n`);
      return;
    }

    // Step 3: Text-only analysis (no screenshot needed)
    console.log(`[${session.id}] 💭 Step 3/4: Running text analysis (no screenshot needed)...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    const startAnalysis = Date.now();
    const result = await textAnalysis(
      transcript,
      emptyUiTree,
      getHistoryForLLM(session),
      signal
    );
    const analysisTime = Date.now() - startAnalysis;
    console.log(`[${session.id}] ✅ Analysis complete (${analysisTime}ms)`);
    console.log(`[${session.id}] 📝 Answer: "${result.answer}"`);
    console.log(`[${session.id}] 🎯 Highlights: ${result.highlights.length} items`);

    await sendAnswer(ws, session, result, signal);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 Audio pipeline aborted`);
      return;
    }
    console.error(`[${session.id}] ❌ Error in audio pipeline:`);
    console.error(err);
    sendError(ws, "Something went wrong processing your request. Please try again.");
  }
}

export async function handleScreenshotResponse(
  ws: WebSocket,
  session: Session,
  message: ScreenshotResponseMessage
): Promise<void> {
  const pending = pendingScreenshots.get(session.id);
  if (!pending) {
    sendError(ws, "No pending screenshot request.");
    return;
  }
  clearTimeout(pending.timer);
  pendingScreenshots.delete(session.id);

  // Use existing session abort controller or create new one
  let controller = sessionAbortControllers.get(session.id);
  if (!controller || controller.signal.aborted) {
    controller = resetSessionAbort(session.id);
  }
  const signal = controller.signal;

  try {
    console.log(`[${session.id}] Running visual analysis with screenshot...`);
    const result = await visualAnalysis(
      pending.userText,
      message.screenshot,
      message.uiTree,
      getHistoryForLLM(session),
      signal
    );

    await sendAnswer(ws, session, result, signal);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 Visual analysis aborted`);
      return;
    }
    console.error(`[${session.id}] Error in visual analysis:`, err);
    sendError(ws, "Something went wrong analyzing the screenshot. Please try again.");
  }
}

export async function handleScreenshotDeclined(
  ws: WebSocket,
  session: Session
): Promise<void> {
  const pending = pendingScreenshots.get(session.id);
  if (!pending) {
    sendError(ws, "No pending screenshot request.");
    return;
  }
  clearTimeout(pending.timer);
  pendingScreenshots.delete(session.id);

  let controller = sessionAbortControllers.get(session.id);
  if (!controller || controller.signal.aborted) {
    controller = resetSessionAbort(session.id);
  }
  const signal = controller.signal;

  try {
    console.log(`[${session.id}] Screenshot declined, falling back to text analysis...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    const result = await textAnalysis(
      pending.userText,
      emptyUiTree,
      getHistoryForLLM(session),
      signal
    );

    await sendAnswer(ws, session, result, signal);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 Fallback analysis aborted`);
      return;
    }
    console.error(`[${session.id}] Error in fallback analysis:`, err);
    sendError(ws, "Something went wrong. Please try again.");
  }
}

async function sendAnswer(
  ws: WebSocket,
  session: Session,
  result: AnalysisResult,
  signal?: AbortSignal
): Promise<void> {
  // Add assistant response to history
  addToHistory(session, "assistant", result.answer);
  console.log(`[${session.id}] 📚 Added assistant response to history`);

  // Check if aborted before TTS
  if (signal?.aborted) {
    console.log(`[${session.id}] 🚫 Aborted before TTS`);
    return;
  }

  // Step 4: TTS
  console.log(`[${session.id}] 🔊 Step 4/4: Generating TTS for "${result.answer.substring(0, 50)}..."`);
  let mp3Buffer: Buffer;
  const startTTS = Date.now();
  try {
    mp3Buffer = await textToSpeech(result.answer, signal);
    const ttsTime = Date.now() - startTTS;
    console.log(`[${session.id}] ✅ TTS generated (${mp3Buffer.length} bytes, ${ttsTime}ms)`);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 TTS aborted`);
      return;
    }
    console.error(`[${session.id}] ❌ TTS failed, sending text-only answer:`);
    console.error(err);
    // Send answer without audio if TTS fails
    const answerMsg: AnswerMessage = {
      type: "answer",
      text: result.answer,
      highlights: result.highlights,
      hasAudio: false,
    };
    sendJSON(ws, answerMsg);
    console.log(`[${session.id}] 📤 Sent text-only answer (no audio)`);
    return;
  }

  // Send answer text message, then binary MP3
  const answerMsg: AnswerMessage = {
    type: "answer",
    text: result.answer,
    highlights: result.highlights,
    hasAudio: true,
  };
  console.log(`[${session.id}] 📤 Sending answer message + MP3 binary`);
  sendJSON(ws, answerMsg);
  sendBinary(ws, mp3Buffer);
  console.log(`[${session.id}] ✅ Complete! Answer sent to client\n`);
}

export function cleanupSession(sessionId: string): void {
  const pending = pendingScreenshots.get(sessionId);
  if (pending) {
    clearTimeout(pending.timer);
  }
  pendingScreenshots.delete(sessionId);
  const controller = sessionAbortControllers.get(sessionId);
  if (controller) {
    controller.abort();
  }
  sessionAbortControllers.delete(sessionId);
}
