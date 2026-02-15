import OpenAI, { toFile } from "openai";

const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

export async function transcribeAudio(wavBuffer: Buffer): Promise<string> {
  console.log(`[Whisper] 🎤 Calling OpenAI Whisper API (${wavBuffer.length} bytes)...`);

  // DEBUG: Save WAV file to inspect audio
  const fs = await import('fs/promises');
  const debugPath = `/tmp/debug-audio-${Date.now()}.wav`;
  await fs.writeFile(debugPath, wavBuffer);
  console.log(`[Whisper] 💾 Saved debug WAV to: ${debugPath}`);

  const file = await toFile(wavBuffer, "audio.wav", { type: "audio/wav" });

  const response = await openai.audio.transcriptions.create({
    model: "whisper-1",
    file,
    response_format: "text",
  });

  const transcript = (response as unknown as string).trim();
  console.log(`[Whisper] ✅ Transcription successful: "${transcript}"`);

  return transcript;
}
