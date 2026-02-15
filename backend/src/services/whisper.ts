import OpenAI, { toFile } from "openai";

const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

export async function transcribeAudio(
  wavBuffer: Buffer,
  signal?: AbortSignal
): Promise<string> {
  console.log(`[Whisper] 🎤 Calling OpenAI Whisper API (${wavBuffer.length} bytes)...`);

  const file = await toFile(wavBuffer, "audio.wav", { type: "audio/wav" });

  const response = await openai.audio.transcriptions.create(
    {
      model: "whisper-1",
      file,
      response_format: "text",
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
