# Know Your Phone - Android Voice Assistant

A hackathon prototype that helps users understand what's on their Android screen using AI-powered voice assistance. The app shows a floating dot overlay, listens to voice questions, and provides spoken answers with visual highlights on UI elements.

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for detailed system design.

```
┌─────────────┐                    ┌──────────────┐
│   Android   │ ←── WebSocket ───→ │   Backend    │
│   Kotlin    │                    │  Node.js/TS  │
└─────────────┘                    └──────────────┘
     │                                     │
     ├─ Overlay UI (dot)                  ├─ Whisper STT
     ├─ Audio recording                   ├─ OpenRouter LLM
     ├─ Accessibility tree                └─ ElevenLabs TTS
     └─ Screenshot capture
```

## Setup

### Backend

1. **Install dependencies:**
   ```bash
   cd backend
   npm install
   ```

2. **Configure API keys:**
   ```bash
   cp .env.example .env
   # Edit .env and fill in your API keys
   ```

   Required API keys:
   - `OPENAI_API_KEY` - For Whisper speech-to-text
   - `OPENROUTER_API_KEY` - For LLM inference (default: GPT-5.2, configurable)
   - `ELEVENLABS_API_KEY` - For text-to-speech
   - `ELEVENLABS_VOICE_ID` - Voice ID (default: Rachel)

   Optional model configuration:
   - `LLM_TEXT_MODEL` - Model for text triage/analysis (default: `openai/gpt-5.2`)
   - `LLM_VISION_MODEL` - Model for visual analysis (default: `openai/gpt-5.2`)

3. **Run the server:**
   ```bash
   npm run dev          # Development with auto-reload
   npm start            # Production
   npm test             # Run tests
   npm run typecheck    # TypeScript validation
   ```

   Server starts on `http://localhost:8765`
   - Health check: `http://localhost:8765/health`
   - WebSocket: `ws://localhost:8765`

### Android

1. **Open the project:**
   ```bash
   cd android
   # Open in Android Studio
   ```

2. **Build requirements:**
   - Android Studio Iguana or later
   - Gradle 8.5
   - Kotlin 1.9.20
   - minSdk 30 (Android 11+)

3. **Setup device connection:**

   **For both emulator and physical device:**
   ```bash
   # Setup ADB port forwarding (run this when device is connected)
   adb reverse tcp:8765 tcp:8765
   ```

   Alternatively, use the provided script:
   ```bash
   ./adb-reverse.sh
   ```

   The app is configured to use `ws://localhost:8765` by default, which works with adb reverse for both emulators and physical devices.

4. **Grant permissions:**
   - Microphone permission
   - Display over other apps
   - Accessibility service (must enable manually in Settings → Accessibility)

## Usage

1. Start the backend server
2. Install and launch the Android app
3. Grant all required permissions
4. Tap "Start Assistant"
5. The floating dot appears - tap it to talk
6. Ask questions like:
   - "Where is the settings button?"
   - "What should I tap next?"
   - "What does this error say?" (may request screenshot)

## State Flow

```
IDLE → LISTENING → THINKING → SPEAKING → [HIGHLIGHTING] → IDLE
                       ↓
              NEED_SCREENSHOT → THINKING → ...
```

### Dot Colors
- **Gray** - Idle, ready to listen
- **Green (pulsing)** - Listening to your voice
- **Yellow (rotating)** - Thinking / processing
- **Blue (pulsing)** - Speaking the answer
- **Orange** - Needs screenshot confirmation
- **Purple** - Showing highlights

## WebSocket Protocol

### Client → Server
```typescript
// 1. Send audio
{"type": "audio_data", "format": "wav", "sampleRate": 16000}
<binary WAV data>

// 2. Confirm screenshot
{"type": "screenshot_response", "screenshot": "<base64>", "uiTree": {...}}

// 3. Decline screenshot
{"type": "screenshot_declined"}
```

### Server → Client
```typescript
// 1. Transcript
{"type": "transcript", "text": "what's on this screen?"}

// 2. Need screenshot
{"type": "need_screenshot", "reason": "Need to see visual content"}

// 3. Answer + audio
{"type": "answer", "text": "This is the Settings screen", "highlights": [...]}
<binary MP3 data>

// 4. Error
{"type": "error", "message": "Something went wrong"}
```

## Backend Testing

```bash
cd backend
npm test              # Run all tests
npm run test:watch    # Watch mode
```

**Test coverage:**
- Protocol message types
- Session management (conversation history)
- Health endpoint structure
- History capping (last 20 messages)

## Conversation Handling

The backend maintains conversation context:
- Each user message is added to session history
- Full history (up to 20 messages) is sent with every LLM request
- System prompt → conversation history → current query
- Enables multi-turn conversations with context

## API Services

The backend uses two separate API services:
- **OpenAI API** - Whisper STT (speech recognition)
- **OpenRouter API** - LLM inference (text triage and multimodal visual analysis)

The default LLM model is `openai/gpt-5.2`, configurable via `LLM_TEXT_MODEL` and `LLM_VISION_MODEL` environment variables.

## Technical Notes

### Backend
- TypeScript type checking: `npx tsc --noEmit`
- Whisper API requires 44-byte WAV header
- Default LLM model: `openai/gpt-5.2` (configurable via `LLM_TEXT_MODEL` and `LLM_VISION_MODEL`)
- Text and vision analysis use the same configurable model

### Android
- AccessibilityService must be manually enabled in Settings → Accessibility
- `getRootInActiveWindow()` returns null during screen transitions
- Screenshot bitmaps must be copied from `HardwareBuffer` to `ARGB_8888` format before JPEG compression
- Device connection requires `adb reverse tcp:8765 tcp:8765` for both emulators and physical devices (can be configured via `./adb-reverse.sh`)
- Dot overlay uses `FLAG_NOT_FOCUSABLE` to receive touches without stealing focus
- Highlight overlay uses `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` for touch passthrough

## Project Structure

```
know-your-phone/
├── ARCHITECTURE.md          # Detailed architecture doc
├── README.md                # This file
├── backend/
│   ├── src/
│   │   ├── index.ts         # Express + WebSocket server
│   │   ├── wsHandler.ts     # Message router
│   │   ├── stateMachine.ts  # STT → LLM → TTS orchestration
│   │   ├── session.ts       # Conversation history
│   │   ├── protocol.ts      # Message types
│   │   ├── prompts.ts       # LLM system prompts
│   │   ├── services/
│   │   │   ├── whisper.ts   # OpenAI Whisper STT
│   │   │   ├── openrouter.ts # Gemini LLM (triage + vision)
│   │   │   └── elevenlabs.ts # TTS
│   │   └── __tests__/       # Jest tests
│   └── package.json
└── android/
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/knowyourphone/app/
            ├── MainActivity.kt
            ├── OverlayService.kt
            ├── KypAccessibilityService.kt
            ├── overlay/         # DotView, HighlightOverlayView, ConfirmPillView
            ├── audio/           # AudioRecorder, AudioPlayer
            ├── network/         # WsClient, Protocol
            ├── state/           # AssistantState, AssistantViewModel
            ├── model/           # UiSnapshot, HighlightTarget
            └── util/            # WavEncoder
```

## Development

### Backend Hot Reload
```bash
cd backend
npm run dev  # tsx watch mode
```

### Android Studio
- Open `android/` folder
- Build → Rebuild Project
- Run on emulator or device (minSdk 30+)

### Logs
- Backend: Console output with session IDs
- Android: Logcat filtered by `KnowYourPhone` or specific tags

## License

MIT
