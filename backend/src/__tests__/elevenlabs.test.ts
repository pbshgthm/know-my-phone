import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import { transcribeAudio } from "../services/elevenlabs.js";

describe("ElevenLabs STT", () => {
  const originalFetch = global.fetch;
  const originalApiKey = process.env.ELEVENLABS_API_KEY;
  const originalSttModelId = process.env.ELEVENLABS_STT_MODEL_ID;

  beforeEach(() => {
    jest.restoreAllMocks();
    process.env.ELEVENLABS_API_KEY = "test-elevenlabs-key";
    delete process.env.ELEVENLABS_STT_MODEL_ID;
  });

  afterEach(() => {
    global.fetch = originalFetch;
    if (originalApiKey === undefined) {
      delete process.env.ELEVENLABS_API_KEY;
    } else {
      process.env.ELEVENLABS_API_KEY = originalApiKey;
    }
    if (originalSttModelId === undefined) {
      delete process.env.ELEVENLABS_STT_MODEL_ID;
    } else {
      process.env.ELEVENLABS_STT_MODEL_ID = originalSttModelId;
    }
  });

  it("throws if ELEVENLABS_API_KEY is missing", async () => {
    delete process.env.ELEVENLABS_API_KEY;
    await expect(transcribeAudio(Buffer.from([1, 2, 3]), "en")).rejects.toThrow(
      "ELEVENLABS_API_KEY not set"
    );
  });

  it("calls ElevenLabs STT endpoint with multipart form data", async () => {
    process.env.ELEVENLABS_STT_MODEL_ID = "scribe_v2";

    let capturedUrl: RequestInfo | URL | undefined;
    let capturedInit: RequestInit | undefined;
    const fetchMock = jest.fn(async (url?: RequestInfo | URL, init?: RequestInit) => {
      capturedUrl = url;
      capturedInit = init;
      return new Response(JSON.stringify({ text: "  hello there  " }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });
    global.fetch = fetchMock as unknown as typeof fetch;

    const transcript = await transcribeAudio(Buffer.from([1, 2, 3]), "ta");

    expect(transcript).toBe("hello there");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(capturedUrl).toBe("https://api.elevenlabs.io/v1/speech-to-text");
    expect(capturedInit).toBeDefined();

    const init = capturedInit as RequestInit;
    expect(init.method).toBe("POST");
    expect((init.headers as Record<string, string>)["xi-api-key"]).toBe(
      "test-elevenlabs-key"
    );

    const body = init.body as FormData;
    expect(body.get("model_id")).toBe("scribe_v2");
    expect(body.get("language_code")).toBe("tam");
    expect(body.get("tag_audio_events")).toBe("false");
    expect(body.get("diarize")).toBe("false");
    expect(body.get("file")).toBeInstanceOf(Blob);
  });

  it("throws when ElevenLabs STT responds with an error", async () => {
    const fetchMock = jest.fn(async () => new Response("bad request", { status: 400 }));
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(transcribeAudio(Buffer.from([1, 2, 3]), "en")).rejects.toThrow(
      "ElevenLabs STT API error 400: bad request"
    );
  });

  it("throws when transcript text is missing from response", async () => {
    const fetchMock = jest.fn(async () =>
      new Response(JSON.stringify({ words: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      })
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(transcribeAudio(Buffer.from([1, 2, 3]), "en")).rejects.toThrow(
      "ElevenLabs STT returned empty transcription"
    );
  });
});
