# Know Your Phone - Android Voice Assistant

A hackathon prototype that helps users understand what's on their Android screen using AI-powered voice assistance. The app shows a floating pill overlay, listens to voice questions, and provides spoken answers with visual highlights on UI elements.

## Architecture

See [ARCHITECTURE.md](./ARCHITECTURE.md) for detailed system design.

```
┌─────────────┐                    ┌──────────────┐
│   Android   │ ←── WebSocket ───→ │   Backend    │
│   Kotlin    │                    │  Node.js/TS  │
└─────────────┘                    └──────────────┘
     │                                     │
     ├─ Overlay UI (pill)                 ├─ ElevenLabs STT
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
   - `OPENROUTER_API_KEY` - For LLM inference (default: GPT-5.2, configurable)
   - `ELEVENLABS_API_KEY` - For speech-to-text and text-to-speech
   - `ELEVENLABS_VOICE_ID` - Voice ID (default: Rachel)

   Optional model configuration:
   - `LLM_TEXT_MODEL` - Model for text triage/analysis (default: `openai/gpt-5.2`)
   - `LLM_VISION_MODEL` - Model for visual analysis (default: `openai/gpt-5.2`)
   - `ELEVENLABS_STREAMING_MODEL` - Global TTS model ID (default: `eleven_v3`)
   - `ELEVENLABS_VOICE_ID_EN|TA|HI|KN|TE|ML` - Per-language TTS voice IDs
   - `ELEVENLABS_STREAMING_MODEL_EN|TA|HI|KN|TE|ML` - Per-language TTS model IDs

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
   ./adb-reverse start
   ```
   Use `./adb-reverse stop` to clear all forwarding, or run `./adb-reverse` for a simple start/stop menu.

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
5. The floating pill appears - press and hold to talk
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

### Pill UI + Interaction
- **Idle**: mic icon only.
- **Listening**: morphs into pill, mic icon + right-side audio-lines (equalizer). Release to send. **No drag** while listening.
- **Thinking**: loader icon only.
- **Need screenshot**: camera icon + “Screenshot”, right-side ✓ / ✕ (no audio during request).
- **Speaking**: sparkles icon + right-side audio-lines.
- **Drag**: allowed in idle/thinking/speaking; locked while listening.

## WebSocket Protocol

### Client → Server
```typescript
// 0. Bind session + language
{"type": "hello", "clientId": "uuid"}
{"type": "set_language", "languageCode": "en|ta|hi|kn|te"}

// 1. Send audio
{"type": "audio_data", "format": "wav", "sampleRate": 16000}
<binary WAV data>

// 2. Confirm screenshot
{"type": "screenshot_response", "screenshot": "<base64>", "uiTree": {...}}

// 3. Decline screenshot
{"type": "screenshot_declined"}

// 4. Reset session (manual)
{"type": "reset_session"}
```

### Server → Client
```typescript
// 1. Transcript
{"type": "transcript", "text": "what's on this screen?"}

// 2. Screenshot request (no audio; modal-only prompt)
{"type": "screenshot_request", "text": "", "reason": "Need to see visual content", "hasAudio": false}

// 3. Answer + audio
{"type": "answer", "text": "This is the Settings screen", "highlights": [...], "hasAudio": true}
<binary MP3 data>

// 4. Session status (message counts)
{"type": "session_status", "sessionId": "uuid", "userCount": 3, "assistantCount": 3}

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
- **ElevenLabs API** - STT + TTS (speech recognition and speech synthesis)
- **OpenRouter API** - LLM inference (text triage and multimodal visual analysis)

The default LLM model is `openai/gpt-5.2`, configurable via `LLM_TEXT_MODEL` and `LLM_VISION_MODEL` environment variables.

## Technical Notes

### Backend
- TypeScript type checking: `npx tsc --noEmit`
- Android client sends WAV audio with a 44-byte header for backend STT uploads
- Default LLM model: `openai/gpt-5.2` (configurable via `LLM_TEXT_MODEL` and `LLM_VISION_MODEL`)
- Text and vision analysis use the same configurable model

### Android
- AccessibilityService must be manually enabled in Settings → Accessibility
- `getRootInActiveWindow()` returns null during screen transitions
- Screenshot bitmaps must be copied from `HardwareBuffer` to `ARGB_8888` format before JPEG compression
- Device connection requires `adb reverse tcp:8765 tcp:8765` for both emulators and physical devices (can be configured via `./adb-reverse`)
- Overlay pill uses `FLAG_NOT_FOCUSABLE` to receive touches without stealing focus
- Highlight overlay uses `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` for touch passthrough
- Press-and-hold starts recording; release sends. Drag is disabled while listening.

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
│   │   │   ├── openrouter.ts # OpenRouter LLM (triage + vision)
│   │   │   └── elevenlabs.ts # ElevenLabs STT + TTS
│   │   └── __tests__/       # Jest tests
│   └── package.json
└── android/
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/knowyourphone/app/
            ├── MainActivity.kt
            ├── OverlayService.kt
            ├── KypAccessibilityService.kt
            ├── overlay/         # DotView, HighlightOverlayView
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
