import type WebSocket from "ws";
import type { Session } from "./session.js";
import type {
  ScreenshotResponseMessage,
  AnalysisResult,
  UiTree,
} from "./protocol.js";
import { addToHistory, getHistoryForLLM, checkInactivityReset, startNewConversation } from "./session.js";
import { transcribeAudio, streamTextToSpeech } from "./services/elevenlabs.js";
import {
  triageQuery,
  streamVisualAnalysis,
  streamTextAnalysis,
} from "./services/openrouter.js";
import {
  initConversation,
  startTurn,
  updateTurn,
  saveAudioInput,
  saveAudioOutput,
  saveScreenshot,
  saveUiTree,
} from "./dataStore.js";
import { StreamingAnswerParser, stripAudioTags } from "./streamParser.js";

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

/** Fire-and-forget helper that logs errors */
function saveTiming(
  clientId: string,
  conversationId: string,
  turnId: number,
  updates: Record<string, unknown>
): void {
  updateTurn(clientId, conversationId, turnId, updates).catch((err) =>
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

/**
 * Apply the 30-minute inactivity check. If triggered, creates a new
 * conversation and initialises it on disk.
 */
async function applyInactivityCheck(session: Session): Promise<void> {
  if (checkInactivityReset(session)) {
    await initConversation(
      session.clientId,
      session.conversationId,
      session.languageCode
    );
  }
}

/**
 * Handle LLM-based session boundary: if the model returned "NEW",
 * start a fresh conversation for subsequent interactions.
 * The current exchange (userText + assistantText) becomes the seed
 * of the new conversation.
 */
async function applyLlmSessionBoundary(
  session: Session,
  conversationStatus: string | undefined,
  userText: string,
  assistantText: string
): Promise<void> {
  if (conversationStatus !== "NEW") return;

  console.log(
    `[${session.id}] 🔀 LLM signalled NEW conversation, creating boundary`
  );

  startNewConversation(session);
  await initConversation(
    session.clientId,
    session.conversationId,
    session.languageCode
  );

  // Seed the new conversation's history with this exchange
  if (userText) addToHistory(session, "user", userText);
  addToHistory(session, "assistant", assistantText);
}

export async function handleAudioReceived(
  ws: WebSocket,
  session: Session,
  audioBuffer: Buffer
): Promise<void> {
  // Create a fresh AbortController for this request (aborts any previous)
  const controller = resetSessionAbort(session.id);
  const signal = controller.signal;

  // --- Auto session boundary: 30-minute inactivity check ---
  await applyInactivityCheck(session);

  // Increment turn counter and start tracking this turn
  session.turnCounter++;
  session.currentTurnId = session.turnCounter;
  const turnId = session.currentTurnId;

  console.log(`\n${'='.repeat(60)}`);
  console.log(`[${session.id}] 🎬 Starting audio processing pipeline (turn #${turnId}, conv=${session.conversationId})`);
  console.log(`${'='.repeat(60)}\n`);

  // Save turn and audio to disk (fire and forget)
  startTurn(session.clientId, session.conversationId, turnId, {
    autoScreenshot: session.autoScreenshot,
  }).catch((err) => console.error(`[DataStore] Failed to start turn:`, err));

  saveAudioInput(session.clientId, session.conversationId, turnId, audioBuffer)
    .then((file) =>
      updateTurn(session.clientId, session.conversationId, turnId, { userAudioFile: file })
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
    console.log(`[${session.id}] 📝 Step 1: Transcribing audio (${audioBuffer.length} bytes)...`);
    saveTiming(session.clientId, session.conversationId, turnId, { sttStartedAt: Date.now() });
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
    saveTiming(session.clientId, session.conversationId, turnId, {
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

          if (msg.redacted) {
            console.log(`[${session.id}] 🔒 Screenshot has PII redactions: ${msg.redactions?.length ?? 0} types`);
          }

          // Save buffered screenshot + UI tree to disk
          saveTiming(session.clientId, session.conversationId, turnId, {
            screenshotReceivedAt: Date.now(),
          });
          saveScreenshot(session.clientId, session.conversationId, turnId, msg.screenshot)
            .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshotFile: file }))
            .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
          saveUiTree(session.clientId, session.conversationId, turnId, msg.uiTree)
            .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { uiTreeFile: file }))
            .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

          saveTiming(session.clientId, session.conversationId, turnId, { llmStartedAt: Date.now() });
          const generator = streamVisualAnalysis(
            transcript,
            msg.screenshot,
            msg.uiTree,
            getHistoryForLLM(session),
            signal,
            session.autoScreenshot,
            msg.redactions
          );
          await streamAnswerToClient(ws, session, generator, signal, transcript);
        }
        // else: screenshot not yet arrived, handleScreenshotResponse will pick it up
      }
      return;
    }

    // Step 2: Triage - does this need a screenshot?
    console.log(`[${session.id}] 🤔 Step 2: Running triage query...`);
    saveTiming(session.clientId, session.conversationId, turnId, { triageStartedAt: Date.now() });
    const startTriage = Date.now();
    const triage = await triageQuery(transcript, getHistoryForLLM(session), signal, session.autoScreenshot);
    const triageTime = Date.now() - startTriage;
    console.log(`[${session.id}] ✅ Triage complete (${triageTime}ms)`);
    console.log(`[${session.id}] 🔍 Triage result: needsScreenshot=${triage.needsScreenshot}, reason="${triage.reason}"`);

    // Save triage result + timing to disk
    saveTiming(session.clientId, session.conversationId, turnId, {
      triageResult: { needsScreenshot: triage.needsScreenshot, reason: triage.reason },
      triageCompletedAt: Date.now(),
    });

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
      saveTiming(session.clientId, session.conversationId, turnId, { screenshotRequestedAt: Date.now() });
      sendJSON(ws, {
        type: "screenshot_request",
        text: "",
        reason: triage.reason,
        hasAudio: false,
      });
      console.log(`[${session.id}] ⏸️  Waiting for screenshot response...\n`);
      return;
    }

    // Step 3: Streaming text analysis (no screenshot needed)
    console.log(`[${session.id}] 💭 Step 3: Running streaming text analysis...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    saveTiming(session.clientId, session.conversationId, turnId, { llmStartedAt: Date.now() });
    const textGenerator = streamTextAnalysis(
      transcript,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot
    );

    await streamAnswerToClient(ws, session, textGenerator, signal, transcript);
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
  saveTiming(session.clientId, session.conversationId, turnId, { screenshotReceivedAt: Date.now() });
  saveScreenshot(session.clientId, session.conversationId, turnId, message.screenshot)
    .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshotFile: file }))
    .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
  saveUiTree(session.clientId, session.conversationId, turnId, message.uiTree)
    .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { uiTreeFile: file }))
    .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

  try {
    if (message.redacted) {
      console.log(`[${session.id}] 🔒 Screenshot has PII redactions: ${message.redactions?.length ?? 0} types`);
    }
    console.log(`[${session.id}] Running streaming visual analysis with screenshot...`);
    saveTiming(session.clientId, session.conversationId, turnId, { llmStartedAt: Date.now() });
    const visualGenerator = streamVisualAnalysis(
      pending.userText,
      message.screenshot,
      message.uiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot,
      message.redactions
    );

    await streamAnswerToClient(ws, session, visualGenerator, signal, pending.userText);
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
    console.log(`[${session.id}] Screenshot declined, falling back to streaming text analysis...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    saveTiming(session.clientId, session.conversationId, turnId, { llmStartedAt: Date.now() });
    const declinedGenerator = streamTextAnalysis(
      pending.userText,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot
    );

    await streamAnswerToClient(ws, session, declinedGenerator, signal, pending.userText);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] 🚫 Fallback analysis aborted`);
      return;
    }
    console.error(`[${session.id}] Error in fallback analysis:`, err);
    sendError(ws, "Something went wrong. Please try again.");
  }
}

/**
 * Stream LLM output through sentence-level TTS and send PCM chunks to the client.
 * Sends answer_start only when the first audio chunk is ready (client stays in
 * THINKING until then). On error, sends answer_end with whatever text we have.
 *
 * After the response is fully parsed, applies the LLM-based session boundary
 * logic: if conversationStatus is "NEW", a fresh conversation is started.
 */
async function streamAnswerToClient(
  ws: WebSocket,
  session: Session,
  analysisGenerator: AsyncGenerator<string>,
  signal: AbortSignal,
  userText: string
): Promise<void> {
  const parser = new StreamingAnswerParser();
  const turnId = session.currentTurnId;
  // Capture conversationId at the start — if the LLM says NEW we'll switch later
  const convId = session.conversationId;
  let sentenceCount = 0;
  let streamingStarted = false;
  let firstTokenRecorded = false;
  let firstAudioRecorded = false;
  const pcmChunks: Buffer[] = [];

  /** Send answer_start right before the first PCM chunk, so the client
   *  stays in THINKING until audio is actually ready to play. */
  function ensureStreamStarted(): void {
    if (!streamingStarted) {
      if (!firstAudioRecorded) {
        firstAudioRecorded = true;
        saveTiming(session.clientId, convId, turnId, { firstAudioSentAt: Date.now() });
      }
      sendJSON(ws, { type: "answer_start" });
      streamingStarted = true;
    }
  }

  try {
    // Feed LLM chunks to parser, TTS each sentence
    for await (const chunk of analysisGenerator) {
      if (signal.aborted) throw new Error("AbortError");

      if (!firstTokenRecorded) {
        firstTokenRecorded = true;
        saveTiming(session.clientId, convId, turnId, { llmFirstTokenAt: Date.now() });
      }

      const sentences = parser.feed(chunk);

      for (const sentence of sentences) {
        const clean = stripAudioTags(sentence);
        if (!clean) continue;
        sentenceCount++;
        console.log(`[${session.id}] 🔊 Streaming TTS for sentence ${sentenceCount}: "${clean.substring(0, 50)}..."`);

        for await (const pcmChunk of streamTextToSpeech(clean, session.languageCode, signal)) {
          if (signal.aborted) throw new Error("AbortError");
          ensureStreamStarted();
          pcmChunks.push(pcmChunk);
          sendBinary(ws, pcmChunk);
        }
      }
    }

    // LLM streaming done
    saveTiming(session.clientId, convId, turnId, { llmDoneAt: Date.now() });

    // Flush remaining text
    const remaining = parser.flush();
    if (remaining) {
      const clean = stripAudioTags(remaining);
      if (clean) {
        sentenceCount++;
        console.log(`[${session.id}] 🔊 Streaming TTS for final fragment: "${clean.substring(0, 50)}..."`);
        for await (const pcmChunk of streamTextToSpeech(clean, session.languageCode, signal)) {
          if (signal.aborted) throw new Error("AbortError");
          ensureStreamStarted();
          pcmChunks.push(pcmChunk);
          sendBinary(ws, pcmChunk);
        }
      }
    }

    // Parse full JSON for highlights + conversationStatus
    const rawOutput = parser.getRawOutput();
    let cleanAnswer = "";
    let highlights: Array<{ elementId: string; label: string }> = [];
    let conversationStatus: string | undefined;
    try {
      let cleaned = rawOutput.trim();
      if (cleaned.startsWith("```")) {
        cleaned = cleaned.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
      }
      const parsed = JSON.parse(cleaned) as AnalysisResult;
      cleanAnswer = stripAudioTags(parsed.answer);
      highlights = parsed.highlights || [];
      conversationStatus = parsed.conversationStatus;
    } catch {
      // If JSON parse fails, use the extracted answer text
      cleanAnswer = stripAudioTags(parser.flush() || rawOutput);
    }

    // Update last message timestamp
    session.lastMessageTimestamp = Date.now();

    // --- LLM-based session boundary ---
    // If the model signalled NEW, start a fresh conversation.
    // The current turn's artifacts stay in the old conversation (convId),
    // but the new conversation gets seeded with this exchange's history.
    if (conversationStatus === "NEW") {
      await applyLlmSessionBoundary(session, conversationStatus, userText, cleanAnswer);
    } else {
      // Normal CONTINUE path: append to existing history
      if (userText) addToHistory(session, "user", userText);
      addToHistory(session, "assistant", cleanAnswer);
    }

    // Save timing data (to the original conversation where turn artifacts live)
    saveTiming(session.clientId, convId, turnId, {
      assistantText: cleanAnswer,
      highlights,
      conversationStatus,
      sentenceCount,
      completedAt: Date.now(),
    });

    // Save audio output to disk for debugging (fire and forget)
    if (pcmChunks.length > 0) {
      saveAudioOutput(session.clientId, convId, turnId, pcmChunks)
        .then((file) => updateTurn(session.clientId, convId, turnId, { assistantAudioFile: file }))
        .catch((err) => console.error(`[DataStore] Failed to save audio output:`, err));
    }

    // Send answer_end with text and highlights
    sendJSON(ws, { type: "answer_end", text: cleanAnswer, highlights });
    console.log(`[${session.id}] ✅ Streaming complete! ${sentenceCount} sentences sent (status=${conversationStatus ?? "CONTINUE"})`);
  } catch (err) {
    if ((err as Error).name === "AbortError" || (err as Error).message === "AbortError") {
      console.log(`[${session.id}] 🚫 Streaming pipeline aborted`);
      return;
    }
    console.error(`[${session.id}] ❌ Streaming pipeline failed:`, err);

    // Send answer_end with whatever text we managed to extract
    const rawOutput = parser.getRawOutput();
    let cleanAnswer = "";
    let highlights: Array<{ elementId: string; label: string }> = [];
    try {
      let cleaned = rawOutput.trim();
      if (cleaned.startsWith("```")) {
        cleaned = cleaned.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
      }
      const parsed = JSON.parse(cleaned) as AnalysisResult;
      cleanAnswer = stripAudioTags(parsed.answer);
      highlights = parsed.highlights || [];
    } catch {
      cleanAnswer = stripAudioTags(parser.flush() || "");
    }

    if (cleanAnswer) {
      session.lastMessageTimestamp = Date.now();
      if (userText) addToHistory(session, "user", userText);
      addToHistory(session, "assistant", cleanAnswer);
      sendJSON(ws, { type: "answer_end", text: cleanAnswer, highlights });
    } else {
      sendError(ws, "Something went wrong processing your request. Please try again.");
    }
  }
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
