const ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1";

const STT_LANGUAGE_CODES: Record<string, string> = {
  en: "eng",
  hi: "hin",
  ta: "tam",
  kn: "kan",
  te: "tel",
};

function resolveVoiceId(languageCode: string): string {
  const voiceByLang: Record<string, string | undefined> = {
    en: process.env.ELEVENLABS_VOICE_ID_EN,
    ta: process.env.ELEVENLABS_VOICE_ID_TA,
    hi: process.env.ELEVENLABS_VOICE_ID_HI,
    kn: process.env.ELEVENLABS_VOICE_ID_KN,
    te: process.env.ELEVENLABS_VOICE_ID_TE,
  };

  return (
    voiceByLang[languageCode] ||
    process.env.ELEVENLABS_VOICE_ID ||
    "21m00Tcm4TlvDq8ikWAM"
  );
}

export async function transcribeAudio(
  wavBuffer: Buffer,
  languageCode: string,
  signal?: AbortSignal
): Promise<string> {
  const apiKey = process.env.ELEVENLABS_API_KEY;
  if (!apiKey) {
    console.error(`[ElevenLabs] ❌ ELEVENLABS_API_KEY not set in environment`);
    throw new Error("ELEVENLABS_API_KEY not set");
  }

  const sttLanguageCode = STT_LANGUAGE_CODES[languageCode];
  const modelId = process.env.ELEVENLABS_STT_MODEL_ID || "scribe_v2";
  const formData = new FormData();
  formData.append("file", new Blob([Uint8Array.from(wavBuffer)], { type: "audio/wav" }), "audio.wav");
  formData.append("model_id", modelId);
  if (sttLanguageCode) {
    formData.append("language_code", sttLanguageCode);
  }
  formData.append("tag_audio_events", "false");
  formData.append("diarize", "false");

  console.log(
    `[ElevenLabs] 🎤 Transcribing audio (${wavBuffer.length} bytes, model: ${modelId}, lang: ${sttLanguageCode || "auto"})...`
  );

  const fetchSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(30_000)])
    : AbortSignal.timeout(30_000);

  const response = await fetch(`${ELEVENLABS_BASE_URL}/speech-to-text`, {
    method: "POST",
    headers: {
      "xi-api-key": apiKey,
    },
    body: formData,
    signal: fetchSignal,
  });

  if (!response.ok) {
    const errorText = await response.text();
    console.error(`[ElevenLabs] ❌ STT API error ${response.status}: ${errorText}`);
    throw new Error(`ElevenLabs STT API error ${response.status}: ${errorText}`);
  }

  const data = (await response.json()) as { text?: string };
  const transcript = data.text?.trim();
  if (!transcript) {
    console.error(`[ElevenLabs] ❌ Empty STT response: ${JSON.stringify(data)}`);
    throw new Error("ElevenLabs STT returned empty transcription");
  }

  console.log(`[ElevenLabs] ✅ Transcription successful: "${transcript}"`);
  return transcript;
}

// Streaming TTS model — use env override or default to eleven_v3
// (same model as the non-streaming text-to-dialogue endpoint).
const STREAMING_TTS_MODEL =
  process.env.ELEVENLABS_STREAMING_MODEL || "eleven_v3";

export async function* streamTextToSpeech(
  text: string,
  languageCode: string,
  signal?: AbortSignal
): AsyncGenerator<Buffer> {
  const apiKey = process.env.ELEVENLABS_API_KEY;
  if (!apiKey) {
    throw new Error("ELEVENLABS_API_KEY not set");
  }

  const voiceId = resolveVoiceId(languageCode);

  console.log(
    `[ElevenLabs] 🔊 Streaming TTS (${text.length} chars, voice: ${voiceId}, model: ${STREAMING_TTS_MODEL})...`
  );

  const fetchSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
    : AbortSignal.timeout(15_000);

  const response = await fetch(
    `${ELEVENLABS_BASE_URL}/text-to-speech/${voiceId}/stream?output_format=pcm_24000`,
    {
      method: "POST",
      headers: {
        "xi-api-key": apiKey,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        text,
        model_id: STREAMING_TTS_MODEL,
        voice_settings: {
          stability: 0.5,
          similarity_boost: 0.75,
        },
      }),
      signal: fetchSignal,
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(
      `ElevenLabs streaming TTS error ${response.status}: ${errorText}`
    );
  }

  // Safety: verify we actually got PCM back, not MP3 (the default).
  // If ElevenLabs ignores the output_format param, Content-Type will be audio/mpeg.
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("mpeg") || contentType.includes("mp3")) {
    throw new Error(
      `ElevenLabs returned MP3 instead of PCM (content-type: ${contentType}). ` +
        `The output_format=pcm_24000 param may not be supported for model ${STREAMING_TTS_MODEL}.`
    );
  }

  if (!response.body) {
    throw new Error("ElevenLabs returned no stream body");
  }

  const reader = response.body.getReader();
  let totalBytes = 0;
  let carryByte: number | null = null; // for 16-bit sample alignment

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      let chunk = Buffer.from(value);

      // Ensure every chunk we yield is sample-aligned (even number of bytes)
      // for 16-bit PCM. Carry over a trailing odd byte to the next chunk.
      if (carryByte !== null) {
        chunk = Buffer.concat([Buffer.from([carryByte]), chunk]);
        carryByte = null;
      }
      if (chunk.length % 2 !== 0) {
        carryByte = chunk[chunk.length - 1];
        chunk = chunk.subarray(0, chunk.length - 1);
      }

      if (chunk.length > 0) {
        totalBytes += chunk.length;
        yield chunk;
      }
    }

    // Yield any remaining carry byte (pad with zero to complete the sample)
    if (carryByte !== null) {
      const last = Buffer.from([carryByte, 0]);
      totalBytes += last.length;
      yield last;
    }
  } finally {
    reader.releaseLock();
  }

  console.log(
    `[ElevenLabs] ✅ Streaming TTS complete (${totalBytes} bytes PCM)`
  );
}
