import type WebSocket from "ws";
import type { Session } from "./session.js";
import type {
  ScreenshotResponseMessage,
  AnalysisResult,
  UiTree,
} from "./protocol.js";
import { addToHistory, getHistoryForLLM, checkInactivityReset } from "./session.js";
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

/** Minimum audio duration in seconds to bother sending to STT. */
const MIN_AUDIO_DURATION_S = 0.6;

/**
 * Parse a WAV buffer header and return the audio duration in seconds.
 * Returns 0 if the header is invalid or too short.
 */
function getWavDurationSeconds(buf: Buffer): number {
  if (buf.length < 44) return 0;
  const sampleRate = buf.readUInt32LE(24);
  const bitsPerSample = buf.readUInt16LE(34);
  const numChannels = buf.readUInt16LE(22);
  const bytesPerSample = (bitsPerSample / 8) * numChannels;
  if (sampleRate === 0 || bytesPerSample === 0) return 0;
  const dataSize = buf.readUInt32LE(40);
  return dataSize / (sampleRate * bytesPerSample);
}

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
  console.log(`[${sessionId}] Session cancelled`);
}

/**
 * Returns device info for datastore records.
 * Falls back to placeholders if audio somehow arrives before hello.
 */
function getSessionDeviceInfo(session: Session): { manufacturer: string; model: string; androidVersion: string } {
  return session.deviceInfo ?? {
    manufacturer: "unknown",
    model: "unknown",
    androidVersion: "unknown",
  };
}

/**
 * Apply the 30-minute inactivity check.
 * Returns true when a new conversation was started and initialized.
 */
async function applyInactivityCheck(session: Session): Promise<boolean> {
  if (checkInactivityReset(session)) {
    await initConversation(
      session.clientId,
      session.conversationId,
      session.languageCode,
      getSessionDeviceInfo(session)
    );
    return true;
  }
  return false;
}

export async function handleAudioReceived(
  ws: WebSocket,
  session: Session,
  audioBuffer: Buffer
): Promise<void> {
  // Discard audio that's too short — likely an accidental tap
  const duration = getWavDurationSeconds(audioBuffer);
  if (duration < MIN_AUDIO_DURATION_S) {
    console.log(`[${session.id}] Audio too short (${duration.toFixed(2)}s < ${MIN_AUDIO_DURATION_S}s), discarding`);
    sendError(ws, "Audio too short. Please hold the button a bit longer.");
    return;
  }

  // Create a fresh AbortController for this request (aborts any previous)
  const controller = resetSessionAbort(session.id);
  const signal = controller.signal;
  const audioReceivedAt = new Date().toISOString();

  // If auto-screenshot, create pending entry BEFORE STT so the client's
  // screenshot_response (sent immediately after audio) can be buffered.
  if (session.autoScreenshot) {
    const timer = setTimeout(() => {
      if (pendingScreenshots.has(session.id)) {
        pendingScreenshots.delete(session.id);
        console.warn(`[${session.id}] Auto-screenshot timed out after 30s`);
        sendError(ws, "Screenshot capture timed out.");
      }
    }, 30_000);

    pendingScreenshots.set(session.id, {
      userText: "",
      reason: "auto-screenshot",
      timer,
    });
    console.log(`[${session.id}] Auto-screenshot ON — pending entry created before STT`);
  }

  try {
    // Step 1: STT
    console.log(`[${session.id}] Step 1: Transcribing audio (${audioBuffer.length} bytes)...`);
    const sttStartedAt = new Date().toISOString();
    const startSTT = Date.now();
    const transcript = await transcribeAudio(audioBuffer, session.languageCode, signal);
    const sttCompletedAt = new Date().toISOString();
    const sttTime = Date.now() - startSTT;
    console.log(`[${session.id}] STT complete (${sttTime}ms)`);
    console.log(`[${session.id}] Transcript: "${transcript}"`);

    if (!transcript || transcript.trim().length === 0) {
      console.warn(`[${session.id}] Empty transcript, sending error to client`);
      // Clean up auto-screenshot pending entry
      const pending = pendingScreenshots.get(session.id);
      if (pending) {
        clearTimeout(pending.timer);
        pendingScreenshots.delete(session.id);
      }
      sendError(ws, "Could not understand audio. Please try again.");
      return;
    }

    // Auto session boundary check should happen only after transcript is valid,
    // so reconnect noise or accidental taps don't create empty conversations.
    const startedNewConversation = await applyInactivityCheck(session);
    if (!startedNewConversation) {
      await initConversation(
        session.clientId,
        session.conversationId,
        session.languageCode,
        getSessionDeviceInfo(session)
      );
    }

    // Start tracking this turn only after transcript validity is confirmed.
    session.turnCounter++;
    session.currentTurnId = session.turnCounter;
    const turnId = session.currentTurnId;

    console.log(`\n${'='.repeat(60)}`);
    console.log(`[${session.id}] Starting audio processing pipeline (turn #${turnId}, conv=${session.conversationId})`);
    console.log(`${'='.repeat(60)}\n`);

    // Save turn and raw audio to disk (fire and forget)
    startTurn(session.clientId, session.conversationId, turnId, {
      autoScreenshot: session.autoScreenshot,
      audioReceivedAt,
    }).catch((err) => console.error(`[DataStore] Failed to start turn:`, err));

    saveAudioInput(session.clientId, session.conversationId, turnId, audioBuffer)
      .then((file) =>
        updateTurn(session.clientId, session.conversationId, turnId, { input: { audioFile: file } })
      )
      .catch((err) => console.error(`[DataStore] Failed to save audio input:`, err));

    // Save transcript + STT timing to disk
    saveTiming(session.clientId, session.conversationId, turnId, {
      input: { transcript },
      timing: { sttStartedAt, sttCompletedAt },
    });

    // Send transcript back to client
    console.log(`[${session.id}] Sending transcript to client`);
    sendJSON(ws, { type: "transcript", text: transcript });

    // Auto-screenshot mode: update pending entry with transcript, check if screenshot already arrived
    if (session.autoScreenshot) {
      const pending = pendingScreenshots.get(session.id);
      if (pending) {
        pending.userText = transcript;
        if (pending.bufferedScreenshot) {
          // Screenshot arrived during STT — process immediately
          console.log(`[${session.id}] Buffered screenshot found, running visual analysis...`);
          clearTimeout(pending.timer);
          const msg = pending.bufferedScreenshot;
          pendingScreenshots.delete(session.id);

          if (msg.redacted) {
            console.log(`[${session.id}] Screenshot has PII redactions: ${msg.redactions?.length ?? 0} types`);
          }

          // Save buffered screenshot + UI tree to disk
          saveTiming(session.clientId, session.conversationId, turnId, {
            timing: { screenshotReceivedAt: new Date().toISOString() },
          });
          saveScreenshot(session.clientId, session.conversationId, turnId, msg.screenshot)
            .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshot: { file } }))
            .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
          saveUiTree(session.clientId, session.conversationId, turnId, msg.uiTree)
            .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshot: { uiTreeFile: file } }))
            .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

          saveTiming(session.clientId, session.conversationId, turnId, { timing: { llmStartedAt: new Date().toISOString() } });
          const generator = streamVisualAnalysis(
            transcript,
            msg.screenshot,
            msg.uiTree,
            getHistoryForLLM(session),
            signal,
            session.autoScreenshot,
            msg.redactions,
            session.deviceInfo,
            msg.visualRedactions
          );
          await streamAnswerToClient(ws, session, generator, signal, transcript);
        }
        // else: screenshot not yet arrived, handleScreenshotResponse will pick it up
      }
      return;
    }

    // Step 2: Triage - does this need a screenshot?
    console.log(`[${session.id}] Step 2: Running triage query...`);
    saveTiming(session.clientId, session.conversationId, turnId, { timing: { triageStartedAt: new Date().toISOString() } });
    const startTriage = Date.now();
    const triage = await triageQuery(transcript, getHistoryForLLM(session), signal, session.autoScreenshot, session.deviceInfo);
    const triageTime = Date.now() - startTriage;
    console.log(`[${session.id}] Triage complete (${triageTime}ms)`);
    console.log(`[${session.id}] Triage result: needsScreenshot=${triage.needsScreenshot}, reason="${triage.reason}"`);

    // Save triage result + timing to disk
    saveTiming(session.clientId, session.conversationId, turnId, {
      triage: { needsScreenshot: triage.needsScreenshot, reason: triage.reason },
      timing: { triageCompletedAt: new Date().toISOString() },
    });

    if (triage.needsScreenshot) {
      // Set up pending screenshot entry (60s timeout)
      const timer = setTimeout(() => {
        if (pendingScreenshots.has(session.id)) {
          pendingScreenshots.delete(session.id);
          console.warn(`[${session.id}] Screenshot request timed out after 60s`);
          sendError(ws, "Screenshot request timed out.");
        }
      }, 60_000);

      pendingScreenshots.set(session.id, {
        userText: transcript,
        reason: triage.reason,
        timer,
      });

      console.log(`[${session.id}] Triage needs screenshot — TTS-streaming spoken request`);
      saveTiming(session.clientId, session.conversationId, turnId, { timing: { screenshotRequestedAt: new Date().toISOString() } });

      // TTS the spoken request and stream as answer_start → PCM → answer_end
      const spokenText = stripAudioTags(triage.spokenRequest || "Let me take a look at your screen to help you with that.");
      const confirmLabel = triage.confirmLabel || "Share screen";

      sendJSON(ws, { type: "answer_start" });
      try {
        for await (const pcmChunk of streamTextToSpeech(spokenText, session.languageCode, signal)) {
          if (signal.aborted) throw new Error("AbortError");
          sendBinary(ws, pcmChunk);
        }
      } catch (err) {
        if ((err as Error).name === "AbortError" || (err as Error).message === "AbortError") {
          console.log(`[${session.id}] Triage TTS aborted`);
          return;
        }
        console.error(`[${session.id}] Triage TTS failed:`, err);
      }

      // Add spoken request to conversation history
      session.lastMessageTimestamp = Date.now();
      addToHistory(session, "user", transcript);
      addToHistory(session, "assistant", spokenText);

      sendJSON(ws, {
        type: "answer_end",
        text: spokenText,
        highlights: [],
        hasNextStep: true,
        confirmLabel,
      });
      console.log(`[${session.id}] Waiting for screenshot response (confirmLabel="${confirmLabel}")...\n`);
      return;
    }

    // Step 3: Streaming text analysis (no screenshot needed)
    console.log(`[${session.id}] Step 3: Running streaming text analysis...`);
    const emptyUiTree: UiTree = {
      screen: { packageName: "unknown", timestamp: Date.now() },
      nodes: [],
    };
    saveTiming(session.clientId, session.conversationId, turnId, { timing: { llmStartedAt: new Date().toISOString() } });
    const textGenerator = streamTextAnalysis(
      transcript,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot,
      session.deviceInfo
    );

    await streamAnswerToClient(ws, session, textGenerator, signal, transcript);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] Audio pipeline aborted`);
      return;
    }
    console.error(`[${session.id}] Error in audio pipeline:`);
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
    console.log(`[${session.id}] STT still running, buffering screenshot...`);
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
  saveTiming(session.clientId, session.conversationId, turnId, { timing: { screenshotReceivedAt: new Date().toISOString() } });
  saveScreenshot(session.clientId, session.conversationId, turnId, message.screenshot)
    .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshot: { file } }))
    .catch((err) => console.error(`[DataStore] Failed to save screenshot:`, err));
  saveUiTree(session.clientId, session.conversationId, turnId, message.uiTree)
    .then((file) => updateTurn(session.clientId, session.conversationId, turnId, { screenshot: { uiTreeFile: file } }))
    .catch((err) => console.error(`[DataStore] Failed to save UI tree:`, err));

  try {
    if (message.redacted) {
      console.log(`[${session.id}] Screenshot has PII redactions: ${message.redactions?.length ?? 0} types`);
    }
    console.log(`[${session.id}] Running streaming visual analysis with screenshot...`);
    saveTiming(session.clientId, session.conversationId, turnId, { timing: { llmStartedAt: new Date().toISOString() } });
    const visualGenerator = streamVisualAnalysis(
      pending.userText,
      message.screenshot,
      message.uiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot,
      message.redactions,
      session.deviceInfo,
      message.visualRedactions
    );

    await streamAnswerToClient(ws, session, visualGenerator, signal, pending.userText);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] Visual analysis aborted`);
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
    saveTiming(session.clientId, session.conversationId, turnId, { timing: { llmStartedAt: new Date().toISOString() } });
    const declinedGenerator = streamTextAnalysis(
      pending.userText,
      emptyUiTree,
      getHistoryForLLM(session),
      signal,
      session.autoScreenshot,
      session.deviceInfo
    );

    await streamAnswerToClient(ws, session, declinedGenerator, signal, pending.userText);
  } catch (err) {
    if ((err as Error).name === "AbortError") {
      console.log(`[${session.id}] Fallback analysis aborted`);
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
  const convId = session.conversationId;
  let sentenceCount = 0;
  let streamingStarted = false;
  let firstTokenRecorded = false;
  let firstAudioRecorded = false;
  let answerStartTime = 0;
  let highlightsSentDuringSpeech = false;
  const pcmChunks: Buffer[] = [];

  /** Send answer_start right before the first PCM chunk, so the client
   *  stays in THINKING until audio is actually ready to play. */
  function ensureStreamStarted(): void {
    if (!streamingStarted) {
      if (!firstAudioRecorded) {
        firstAudioRecorded = true;
        saveTiming(session.clientId, convId, turnId, { timing: { ttsFirstAudioAt: new Date().toISOString() } });
      }
      sendJSON(ws, { type: "answer_start" });
      answerStartTime = Date.now();
      streamingStarted = true;
    }
  }

  /** Check and send highlights during speech if available and 2s elapsed */
  function trySendEarlyHighlights(): void {
    if (highlightsSentDuringSpeech) return;
    if (!streamingStarted || answerStartTime === 0) return;
    const earlyHighlights = parser.getEarlyHighlights();
    if (!earlyHighlights || earlyHighlights.length === 0) return;
    if (Date.now() - answerStartTime < 2000) return;
    highlightsSentDuringSpeech = true;
    sendJSON(ws, { type: "highlights", highlights: earlyHighlights });
    console.log(`[${session.id}] Sent ${earlyHighlights.length} highlights during speech`);
  }

  try {
    // Feed LLM chunks to parser, TTS each sentence
    for await (const chunk of analysisGenerator) {
      if (signal.aborted) throw new Error("AbortError");

      if (!firstTokenRecorded) {
        firstTokenRecorded = true;
        saveTiming(session.clientId, convId, turnId, { timing: { llmFirstTokenAt: new Date().toISOString() } });
      }

      const sentences = parser.feed(chunk);
      trySendEarlyHighlights();

      for (const sentence of sentences) {
        const clean = stripAudioTags(sentence);
        if (!clean) continue;
        sentenceCount++;
        console.log(`[${session.id}] Streaming TTS for sentence ${sentenceCount}: "${clean.substring(0, 50)}..."`);

        for await (const pcmChunk of streamTextToSpeech(clean, session.languageCode, signal)) {
          if (signal.aborted) throw new Error("AbortError");
          ensureStreamStarted();
          pcmChunks.push(pcmChunk);
          sendBinary(ws, pcmChunk);
          trySendEarlyHighlights();
        }
      }
    }

    // LLM streaming done
    saveTiming(session.clientId, convId, turnId, { timing: { llmCompletedAt: new Date().toISOString() } });

    // Flush remaining text
    const remaining = parser.flush();
    if (remaining) {
      const clean = stripAudioTags(remaining);
      if (clean) {
        sentenceCount++;
        console.log(`[${session.id}] Streaming TTS for final fragment: "${clean.substring(0, 50)}..."`);
        for await (const pcmChunk of streamTextToSpeech(clean, session.languageCode, signal)) {
          if (signal.aborted) throw new Error("AbortError");
          ensureStreamStarted();
          pcmChunks.push(pcmChunk);
          sendBinary(ws, pcmChunk);
          trySendEarlyHighlights();
        }
      }
    }

    // Parse full JSON for highlights and nextStep
    const rawOutput = parser.getRawOutput();
    let cleanAnswer = "";
    let highlights: Array<{ elementId: string; label: string }> = [];
    let nextStep = false;
    let confirmLabel = "";
    try {
      let cleaned = rawOutput.trim();
      if (cleaned.startsWith("```")) {
        cleaned = cleaned.replace(/^```(?:json)?\s*/, "").replace(/\s*```$/, "");
      }
      const parsed = JSON.parse(cleaned) as AnalysisResult;
      cleanAnswer = stripAudioTags(parsed.answer);
      highlights = parsed.highlights || [];
      nextStep = parsed.nextStep || false;
      confirmLabel = parsed.confirmLabel || "";
    } catch {
      // If JSON parse fails, use the extracted answer text
      cleanAnswer = stripAudioTags(parser.flush() || rawOutput);
    }

    // Update last message timestamp
    session.lastMessageTimestamp = Date.now();

    // Always append to history
    if (userText) addToHistory(session, "user", userText);
    addToHistory(session, "assistant", cleanAnswer);

    // Save timing data
    saveTiming(session.clientId, convId, turnId, {
      output: { text: cleanAnswer, highlights, nextStep, confirmLabel },
      timing: { sentenceCount, completedAt: new Date().toISOString() },
    });

    // Save audio output to disk for debugging (fire and forget)
    if (pcmChunks.length > 0) {
      saveAudioOutput(session.clientId, convId, turnId, pcmChunks)
        .then((file) => updateTurn(session.clientId, convId, turnId, { output: { audioFile: file } }))
        .catch((err) => console.error(`[DataStore] Failed to save audio output:`, err));
    }

    // When nextStep is true, create a pending screenshot entry so backend is
    // ready for the follow-up screenshot_response from the ✓ button
    if (nextStep) {
      const timer = setTimeout(() => {
        if (pendingScreenshots.has(session.id)) {
          pendingScreenshots.delete(session.id);
          console.warn(`[${session.id}] Next-step screenshot timed out after 60s`);
        }
      }, 60_000);

      pendingScreenshots.set(session.id, {
        userText,
        reason: "next-step confirmation",
        timer,
      });
    }

    // Send answer_end with text, highlights, and next-step info
    const answerEnd: Record<string, unknown> = {
      type: "answer_end",
      text: cleanAnswer,
      highlights,
    };
    if (nextStep) {
      answerEnd.hasNextStep = true;
      answerEnd.confirmLabel = confirmLabel;
    }
    sendJSON(ws, answerEnd);
    console.log(`[${session.id}] Streaming complete! ${sentenceCount} sentences sent${nextStep ? ` (nextStep, label="${confirmLabel}")` : ""}`);
  } catch (err) {
    if ((err as Error).name === "AbortError" || (err as Error).message === "AbortError") {
      console.log(`[${session.id}] Streaming pipeline aborted`);
      return;
    }
    console.error(`[${session.id}] Streaming pipeline failed:`, err);

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
