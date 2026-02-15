const ELEVENLABS_BASE_URL = "https://api.elevenlabs.io/v1";

export async function textToSpeech(text: string): Promise<Buffer> {
  const apiKey = process.env.ELEVENLABS_API_KEY;
  if (!apiKey) {
    console.error(`[ElevenLabs] ❌ ELEVENLABS_API_KEY not set in environment`);
    throw new Error("ELEVENLABS_API_KEY not set");
  }

  const voiceId = process.env.ELEVENLABS_VOICE_ID || "21m00Tcm4TlvDq8ikWAM"; // default: Rachel

  console.log(`[ElevenLabs] 🔊 Generating TTS for text (${text.length} chars, voice: ${voiceId})...`);

  const response = await fetch(
    `${ELEVENLABS_BASE_URL}/text-to-speech/${voiceId}`,
    {
      method: "POST",
      headers: {
        "xi-api-key": apiKey,
        "Content-Type": "application/json",
        Accept: "audio/mpeg",
      },
      body: JSON.stringify({
        text,
        model_id: "eleven_turbo_v2_5",
        voice_settings: {
          stability: 0.5,
          similarity_boost: 0.75,
        },
      }),
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
