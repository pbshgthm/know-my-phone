import type WebSocket from "ws";
import type { Session } from "./session.js";
import type {
  AnswerMessage,
  ScreenshotResponseMessage,
  AnalysisResult,
  UiTree,
} from "./protocol.js";
import { addToHistory, getHistoryForLLM } from "./session.js";
import { transcribeAudio, textToSpeech } from "./services/elevenlabs.js";
import { triageQuery, visualAnalysis, textAnalysis } from "./services/openrouter.js";
import {
  startTurn,
  updateTurn,
  saveAudioInput,
  saveScreenshot,
  saveUiTree,
  saveAudioOutput,
} from "./dataStore.js";

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

/**
 * Strip ElevenLabs v3 audio tags (e.g. [warmly], [cheerfully]) from text
 * before storing in conversation history, so tags don't accumulate in context.
 */
function stripAudioTags(text: string): string {
  return text.replace(/\[[\w\s]+\]\s*/g, "").trim();
}

/** Fire-and-forget helper that logs errors */
function saveTiming(
  clientId: string,
  sessionId: string,
  turnId: number,
  updates: Record<string, unknown>
): void {
  updateTurn(clientId, sessionId, turnId, updates).catch((err) =>
    console.error(`[DataStore] Failed to save timing:`, err)
  );
}

interface PendingScreenshotRequest {
  userText: string;
  reason: string;
  timer: ReturnType<typeof setTimeout>;
  bufferedScreenshot?: ScreenshotResponseMessage;
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

  // Increment turn counter and start tracking this turn
  session.turnCounter++;
  session.currentTurnId = session.turnCounter;
  const turnId = session.currentTurnId;

  console.log(`\n${'='.repeat(60)}`);
  console.log(`[${session.id}] 🎬 Starting audio processing pipeline (turn #${turnId})`);
  console.log(`${'='.repeat(60)}\n`);

  // Save turn and audio to disk (fire and forget)
  startTurn(session.clientId, session.id, turnId, {
    autoScreenshot: session.autoScreenshot,
  }).catch((err) => console.error(`[DataStore] Failed to start turn:`, err));

  saveAudioInput(session.clientId, session.id, turnId, audioBuffer)
    .then((file) =>
      updateTurn(session.clientId, session.id, turnId, { userAudioFile: file })
    )
    .catch((err) => console.error(`[DataStore] Failed to save audio input:`, err));

  // If auto-screenshot, create pending entry BEFORE STT so the client's
  // screenshot_response (sent immediately after audio) can be buffered.
  if (session.autoScreenshot) {
    const timer = setTimeout(() => {
      if (pendingScreenshots.has(session.id)) {
        pendingScreenshots.delete(session.id);
        console.warn(`[${session.id}] ⏰ Auto-screenshot timed out after 30s`);
        sendError(ws, "Screenshot capture timed out.");
      }
    }, 30_000);

    pendingScreenshots.set(session.id, {
      userText: "",
      reason: "auto-screenshot",
      timer,
    });
    console.log(`[${session.id}] 📸 Auto-screenshot ON — pending entry created before STT`);
  }

  try {
    // Step 1: STT
    console.log(`[${session.id}] 📝 Step 1/4: Transcribing audio (${audioBuffer.length} bytes)...`);
    saveTiming(session.clientId, session.id, turnId, { sttStartedAt: Date.now() });
    const startSTT = Date.now();
    const transcript = await transcribeAudio(audioBuffer, session.languageCode, signal);
    const sttTime = Date.now() - startSTT;
    console.log(`[${session.id}] ✅ STT complete (${sttTime}ms)`);
    console.log(`[${session.id}] 💬 Transcript: "${transcript}"`);

    if (!transcript || transcript.trim().length === 0) {
      console.warn(`[${session.id}] ⚠️  Empty transcript, sending error to client`);
      // Clean up auto-screenshot pending entry
      const pending = pendingScreenshots.get(session.id);
      if (pending) {
        clearTimeout(pending.timer);
        pendingScreenshots.delete(session.id);
      }
      sendError(ws, "Could not understand audio. Please try again.");
      return;
    }

    // Save transcript + STT timing to disk
    saveTiming(session.clientId, session.id, turnId, {
      userTranscript: transcript,
      sttCompletedAt: Date.now(),
    });

    // Send transcript back to client
    console.log(`[${session.id}] 📤 Sending transcript to client`);
    sendJSON(ws, { type: "transcript", text: transcript });

    // Auto-screenshot mode: update pending entry with transcript, check if screenshot already arrived
    if (session.autoScreenshot) {
      const pending = pendingScreenshots.get(session.id);
      if (pending) {
        pending.userText = transcript;
        if (pending.bufferedScreenshot) {
          // Screenshot arrived during STT — process immediately
          console.log(`[${session.id}] 📸 Buffered screenshot found, running visual analysis...`);
          clearTimeout(pending.timer);
          const msg = pending.bufferedScreenshot;
          pendingScreenshots.delete(session.id);

          // Save buffered screenshot + UI tree to disk
          saveTiming(session.clientId, session.id, turnId, {
            screenshotReceivedAt: Date.now(),
          });
          saveScreenshot(session.clientId, session.id, turnId, msg.screenshot)
            .then((file) => updateTurn(session.clientId, session.id, turnId, { screenshotFile: file }))
            .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
          saveUiTree(session.clientId, session.id, turnId, msg.uiTree)
            .then((file) => updateTurn(session.clientId, session.id, turnId, { uiTreeFile: file }))
            .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

          saveTiming(session.clientId, session.id, turnId, { analysisStartedAt: Date.now() });
          const result = await visualAnalysis(
            transcript,
            msg.screenshot,
            msg.uiTree,
            getHistoryForLLM(session),
            signal,
            session.autoScreenshot
          );
          saveTiming(session.clientId, session.id, turnId, { analysisCompletedAt: Date.now() });
          await sendAnswer(ws, session, result, signal, transcript);
        }
        // else: screenshot not yet arrived, handleScreenshotResponse will pick it up
      }
      return;
    }

    // Step 2: Triage - does this need a screenshot?
    // NOTE: User message is NOT added to history yet to avoid duplication
    // (triage and analysis functions append the user message themselves)
    console.log(`[${session.id}] 🤔 Step 2/4: Running triage query...`);
    saveTiming(session.clientId, session.id, turnId, { triageStartedAt: Date.now() });
    const startTriage = Date.now();
    const triage = await triageQuery(transcript, getHistoryForLLM(session), signal, session.autoScreenshot);
    const triageTime = Date.now() - startTriage;
    console.log(`[${session.id}] ✅ Triage complete (${triageTime}ms)`);
    console.log(`[${session.id}] 🔍 Triage result: needsScreenshot=${triage.needsScreenshot}, reason="${triage.reason}"`);

    // Save triage result + timing to disk
    saveTiming(session.clientId, session.id, turnId, {
      triageResult: { needsScreenshot: triage.needsScreenshot, reason: triage.reason },
      triageCompletedAt: Date.now(),
    });

    if (triage.needsScreenshot) {
      // Store pending request with TTL and ask client for screenshot (no audio)
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

      console.log(`[${session.id}] 📸 Requesting screenshot from client (no audio)`);
      saveTiming(session.clientId, session.id, turnId, { screenshotRequestedAt: Date.now() });
      sendJSON(ws, {
        type: "screenshot_request",
        text: "",
        reason: triage.reason,
        hasAudio: false,
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
    saveTiming(session.clientId, session.id, turnId, { analysisStartedAt: Date.now() });
    const startAnalysis = Date.now();
    const result = await textAnalysis(
      transcript,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot
    );
    const analysisTime = Date.now() - startAnalysis;
    console.log(`[${session.id}] ✅ Analysis complete (${analysisTime}ms)`);
    console.log(`[${session.id}] 📝 Answer: "${result.answer}"`);
    console.log(`[${session.id}] 🎯 Highlights: ${result.highlights.length} items`);
    saveTiming(session.clientId, session.id, turnId, { analysisCompletedAt: Date.now() });

    await sendAnswer(ws, session, result, signal, transcript);
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

  // If STT hasn't completed yet (auto-screenshot mode), buffer the screenshot
  if (!pending.userText) {
    console.log(`[${session.id}] 📸 STT still running, buffering screenshot...`);
    pending.bufferedScreenshot = message;
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

  // Save screenshot and UI tree to disk
  const turnId = session.currentTurnId;
  saveTiming(session.clientId, session.id, turnId, { screenshotReceivedAt: Date.now() });
  saveScreenshot(session.clientId, session.id, turnId, message.screenshot)
    .then((file) => updateTurn(session.clientId, session.id, turnId, { screenshotFile: file }))
    .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
  saveUiTree(session.clientId, session.id, turnId, message.uiTree)
    .then((file) => updateTurn(session.clientId, session.id, turnId, { uiTreeFile: file }))
    .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

  try {
    console.log(`[${session.id}] Running visual analysis with screenshot...`);
    saveTiming(session.clientId, session.id, turnId, { analysisStartedAt: Date.now() });
    const result = await visualAnalysis(
      pending.userText,
      message.screenshot,
      message.uiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot
    );
    saveTiming(session.clientId, session.id, turnId, { analysisCompletedAt: Date.now() });

    await sendAnswer(ws, session, result, signal, pending.userText);
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

  const turnId = session.currentTurnId;

  try {
    console.log(`[${session.id}] Screenshot declined, falling back to text analysis...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    saveTiming(session.clientId, session.id, turnId, { analysisStartedAt: Date.now() });
    const result = await textAnalysis(
      pending.userText,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot
    );
    saveTiming(session.clientId, session.id, turnId, { analysisCompletedAt: Date.now() });

    await sendAnswer(ws, session, result, signal, pending.userText);
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
  signal?: AbortSignal,
  userText?: string
): Promise<void> {
  // Add user message to history (deferred until after all LLM calls to avoid duplication)
  if (userText) {
    addToHistory(session, "user", userText);
    console.log(`[${session.id}] 📚 Added user message to history`);
  }

  // Add assistant response to history (strip audio tags so they don't accumulate)
  const cleanAnswer = stripAudioTags(result.answer);
  addToHistory(session, "assistant", cleanAnswer);
  console.log(`[${session.id}] 📚 Added assistant response to history`);

  // Save assistant response to disk
  const turnId = session.currentTurnId;
  saveTiming(session.clientId, session.id, turnId, {
    assistantText: cleanAnswer,
    highlights: result.highlights,
  });

  // Check if aborted before TTS
  if (signal?.aborted) {
    console.log(`[${session.id}] 🚫 Aborted before TTS`);
    return;
  }

  // Step 4: TTS (send original text with audio tags for expressive delivery)
  console.log(`[${session.id}] 🔊 Step 4/4: Generating TTS for "${result.answer.substring(0, 50)}..."`);
  saveTiming(session.clientId, session.id, turnId, { ttsStartedAt: Date.now() });
  let mp3Buffer: Buffer;
  const startTTS = Date.now();
  try {
    mp3Buffer = await textToSpeech(result.answer, session.languageCode, signal);
    const ttsTime = Date.now() - startTTS;
    console.log(`[${session.id}] ✅ TTS generated (${mp3Buffer.length} bytes, ${ttsTime}ms)`);
    saveTiming(session.clientId, session.id, turnId, { ttsCompletedAt: Date.now() });

    // Save TTS audio to disk
    saveAudioOutput(session.clientId, session.id, turnId, mp3Buffer)
      .then((file) => updateTurn(session.clientId, session.id, turnId, { assistantAudioFile: file }))
      .catch((err) => console.error(`[DataStore] Failed to save TTS audio:`, err));
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 TTS aborted`);
      return;
    }
    console.error(`[${session.id}] ❌ TTS failed, sending text-only answer:`);
    console.error(err);
    saveTiming(session.clientId, session.id, turnId, { ttsCompletedAt: Date.now() });
    // Send answer without audio if TTS fails
    const answerMsg: AnswerMessage = {
      type: "answer",
      text: cleanAnswer,
      highlights: result.highlights,
      hasAudio: false,
    };
    sendJSON(ws, answerMsg);
    saveTiming(session.clientId, session.id, turnId, { completedAt: Date.now() });
    console.log(`[${session.id}] 📤 Sent text-only answer (no audio)`);
    return;
  }

  // Send answer text message, then binary MP3
  const answerMsg: AnswerMessage = {
    type: "answer",
    text: cleanAnswer,
    highlights: result.highlights,
    hasAudio: true,
  };
  console.log(`[${session.id}] 📤 Sending answer message + MP3 binary`);
  sendJSON(ws, answerMsg);
  sendBinary(ws, mp3Buffer);
  saveTiming(session.clientId, session.id, turnId, { completedAt: Date.now() });
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
