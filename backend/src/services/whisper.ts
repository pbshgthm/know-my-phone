import OpenAI, { toFile } from "openai";

const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

const LANGUAGE_PROMPTS: Record<string, string> = {
  en: "Transcribe this audio in English.",
  hi: "इस ऑडियो को हिन्दी में लिप्यंतरित करें।",
  ta: "இந்த ஆடியோவை தமிழில் எழுத்துப்பெயர்க்கவும்.",
  kn: "ಈ ಆಡಿಯೊವನ್ನು ಕನ್ನಡದಲ್ಲಿ ಲಿಖಿತ ರೂಪಕ್ಕೆ ಪರಿವರ್ತಿಸಿ.",
  te: "ఈ ఆడియోను తెలుగులో లిప్యంతరం చేయండి.",
};

export async function transcribeAudio(
  wavBuffer: Buffer,
  languageCode: string,
  signal?: AbortSignal
): Promise<string> {
  console.log(`[Whisper] 🎤 Calling OpenAI Whisper API (${wavBuffer.length} bytes)...`);

  const lang = LANGUAGE_PROMPTS[languageCode] ? languageCode : "en";
  const file = await toFile(wavBuffer, "audio.wav", { type: "audio/wav" });

  const response = await openai.audio.transcriptions.create(
    {
      model: "whisper-1",
      file,
      response_format: "text",
      language: lang,
      prompt: LANGUAGE_PROMPTS[lang],
    },
    {
      signal: signal
        ? AbortSignal.any([signal, AbortSignal.timeout(30_000)])
        : AbortSignal.timeout(30_000),
    }
  );

  const transcript = (response as unknown as string).trim();
  console.log(`[Whisper] ✅ Transcription successful: "${transcript}"`);

  return transcript;
}
