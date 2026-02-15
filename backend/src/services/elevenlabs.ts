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

export async function textToSpeech(
  text: string,
  languageCode: string,
  signal?: AbortSignal
): Promise<Buffer> {
  const apiKey = process.env.ELEVENLABS_API_KEY;
  if (!apiKey) {
    console.error(`[ElevenLabs] ❌ ELEVENLABS_API_KEY not set in environment`);
    throw new Error("ELEVENLABS_API_KEY not set");
  }

  const voiceId = resolveVoiceId(languageCode);

  console.log(`[ElevenLabs] 🔊 Generating TTS via text-to-dialogue (${text.length} chars, voice: ${voiceId}, lang: ${languageCode})...`);

  const fetchSignal = signal
    ? AbortSignal.any([signal, AbortSignal.timeout(15_000)])
    : AbortSignal.timeout(15_000);

  const response = await fetch(
    `${ELEVENLABS_BASE_URL}/text-to-dialogue?output_format=mp3_44100_128`,
    {
      method: "POST",
      headers: {
        "xi-api-key": apiKey,
        "Content-Type": "application/json",
        Accept: "audio/mpeg",
      },
      body: JSON.stringify({
        inputs: [
          {
            text,
            voice_id: voiceId,
          },
        ],
        model_id: "eleven_v3",
        language_code: languageCode,
        settings: {
          stability: 1.0,
        },
        apply_text_normalization: "on",
      }),
      signal: fetchSignal,
    }
  );

  if (!response.ok) {
    const errorText = await response.text();
    console.error(`[ElevenLabs] ❌ API error ${response.status}: ${errorText}`);
    throw new Error(`ElevenLabs API error ${response.status}: ${errorText}`);
  }

  const arrayBuffer = await response.arrayBuffer();
  const buffer = Buffer.from(arrayBuffer);
  console.log(`[ElevenLabs] ✅ TTS generated successfully (${buffer.length} bytes MP3)`);

  return buffer;
}
