const ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1";

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
