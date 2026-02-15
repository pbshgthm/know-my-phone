# Architecture: Android "Screen Support" Voice Assistant

## Overview

An Android voice assistant that helps users understand what's on their screen and guides them to the next action. The app shows a floating dot overlay, listens to voice questions, and provides spoken answers with visual highlights on UI elements.

## System Architecture

```
┌─────────────────────────────────────────────┐
│              Android Device                  │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │          OverlayService              │   │
│  │  ┌─────────┐  ┌──────────────────┐  │   │
│  │  │ DotView │  │ HighlightOverlay │  │   │
│  │  └────┬────┘  └──────────────────┘  │   │
│  │       │                              │   │
│  │  ┌────┴────┐  ┌──────────────────┐  │   │
│  │  │Confirm  │  │  AudioRecorder   │  │   │
│  │  │PillView │  │  AudioPlayer     │  │   │
│  │  └─────────┘  └──────────────────┘  │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │     KypAccessibilityService          │   │
│  │  - UI tree collection                │   │
│  │  - Screenshot capture                │   │
│  └──────────────────────────────────────┘   │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │     AssistantViewModel               │   │
│  │  - State management (StateFlow)      │   │
│  │  - Coordinates all components        │   │
│  └──────────────────┬───────────────────┘   │
│                     │ WebSocket              │
└─────────────────────┼───────────────────────┘
                      │
              ┌───────┴───────┐
              │   Network     │
              └───────┬───────┘
                      │
┌─────────────────────┼───────────────────────┐
│           Node.js Backend                    │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │     Express + WebSocket Server       │   │
│  └──────────────────┬───────────────────┘   │
│                     │                        │
│  ┌──────────────────┴───────────────────┐   │
│  │         State Machine                 │   │
│  │   STT → Triage → [Screenshot?]       │   │
│  │        → Analysis → TTS              │   │
│  └──────────────────┬───────────────────┘   │
│                     │                        │
│  ┌─────────┐ ┌─────┴─────┐ ┌───────────┐  │
│  │ Whisper │ │ OpenRouter │ │ElevenLabs │  │
│  │  (STT)  │ │   (LLM)   │ │   (TTS)   │  │
│  └─────────┘ └───────────┘ └───────────┘  │
└──────────────────────────────────────────────┘
```

## Project Structure

```
know-your-phone/
├── DESCRIPTION.md
├── ARCHITECTURE.md
├── backend/
│   ├── package.json
│   ├── tsconfig.json
│   ├── .env.example
│   └── src/
│       ├── index.ts              # Express + WS server entry
│       ├── wsHandler.ts          # WebSocket message router
│       ├── session.ts            # Per-client session + conversation history
│       ├── stateMachine.ts       # Orchestrator: STT → LLM → TTS flow
│       ├── protocol.ts           # Message type definitions
│       ├── prompts.ts            # LLM system prompts (triage + visual analysis)
│       └── services/
│           ├── whisper.ts        # OpenAI Whisper STT
│           ├── openrouter.ts     # OpenRouter LLM (triage + multimodal)
│           └── elevenlabs.ts     # ElevenLabs TTS
└── android/
    └── app/src/main/
        ├── AndroidManifest.xml
        ├── res/xml/accessibility_service_config.xml
        └── java/com/knowyourphone/app/
            ├── MainActivity.kt               # Onboarding + permissions
            ├── KypAccessibilityService.kt     # UI tree + screenshot capture
            ├── OverlayService.kt              # Foreground service: dot + highlights
            ├── overlay/
            │   ├── DotView.kt                 # Floating dot with state + connection animations
            │   ├── HighlightOverlayView.kt    # Draws circles/labels on targets
            │   ├── ConfirmPillView.kt         # "Confirm screenshot?" pill
            │   └── ErrorToastView.kt          # Auto-dismiss error overlay
            ├── audio/
            │   ├── AudioRecorder.kt           # 16kHz mono PCM recording
            │   └── AudioPlayer.kt             # MP3 playback for TTS
            ├── network/
            │   ├── WsClient.kt                # OkHttp WebSocket client
            │   └── Protocol.kt                # Message types (mirrors backend)
            ├── state/
            │   ├── AssistantState.kt           # State enum + StateFlow
            │   └── AssistantViewModel.kt       # Coordinates all components
            ├── model/
            │   ├── UiSnapshot.kt               # Accessibility tree data class
            │   └── HighlightTarget.kt          # Highlight coordinates data class
            └── util/
                └── WavEncoder.kt               # Wraps PCM in WAV header
```

## WebSocket Protocol

### Client → Server

| Type | Frame | Payload |
|---|---|---|
| `audio_data` | text then binary | `{"type":"audio_data","format":"wav","sampleRate":16000}` then WAV bytes |
| `screenshot_response` | text | `{"type":"screenshot_response","screenshot":"<base64>","uiTree":{...}}` |
| `screenshot_declined` | text | `{"type":"screenshot_declined"}` |
| `cancel` | text | `{"type":"cancel"}` — abort current request |

### Server → Client

| Type | Frame | Payload |
|---|---|---|
| `transcript` | text | `{"type":"transcript","text":"..."}` |
| `need_screenshot` | text | `{"type":"need_screenshot","reason":"..."}` |
| `answer` | text, optionally then binary | `{"type":"answer","text":"...","highlights":[...],"hasAudio":true}` then MP3 bytes. If `hasAudio` is false, no binary frame follows. |
| `error` | text | `{"type":"error","message":"..."}` |
| `cancelled` | text | `{"type":"cancelled"}` — acknowledges cancellation |

### Highlight Format
```json
{
  "elementId": "n_23a9",
  "label": "Tap here",
  "bounds": {"left": 80, "top": 920, "right": 500, "bottom": 980}
}
```

## State Machine

### Android App States
```
IDLE → LISTENING → THINKING → SPEAKING → HIGHLIGHTING → IDLE
                       ↓
              NEED_SCREENSHOT → THINKING → SPEAKING → HIGHLIGHTING → IDLE

Interruption (tap dot to cancel and start new recording):
  THINKING → cancel → LISTENING
  SPEAKING → stop audio + cancel → LISTENING
  NEED_SCREENSHOT → cancel → LISTENING
```

### Dot Colors
| State | Fill Color | Border |
|---|---|---|
| IDLE | Gray | White |
| LISTENING | Green (pulsing) | White |
| THINKING | Amber (rotating) | White |
| SPEAKING | Blue (pulsing) | White |
| NEED_SCREENSHOT | Orange | White |
| HIGHLIGHTING | Purple | White |
| Disconnected | (any) | Red |
| Reconnecting | (any) | Yellow (pulsing) |

### Backend Flow
```
1. Receive audio_data (WAV)
2. Whisper STT → transcript text
3. Send transcript to client
4. LLM triage (text-only with conversation history)
   a. If needsScreenshot=false → answer + highlights ready
   b. If needsScreenshot=true → send need_screenshot to client, wait
5. If screenshot received → LLM visual analysis (multimodal)
6. ElevenLabs TTS → MP3 audio
7. Send answer (text + highlights) then MP3 binary to client
```

## Key Technical Details

### Android
- **Overlay**: `WindowManager` with `TYPE_APPLICATION_OVERLAY`
- **Dot**: `FLAG_NOT_FOCUSABLE` (receives touches but doesn't steal focus)
- **Highlights**: `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE` (touch passthrough)
- **AccessibilityService**: `getRootInActiveWindow()` for UI tree, `takeScreenshot()` for capture
- **Screenshot bitmap**: Must copy from `HardwareBuffer` to `ARGB_8888` before `compress()`
- **Audio recording**: 16kHz mono 16-bit PCM on `Dispatchers.IO`
- **WAV header**: 44-byte header required for Whisper API

### Backend
- **Whisper**: OpenAI API, accepts WAV audio
- **LLM**: OpenRouter API for both text triage and multimodal visual analysis
- **TTS**: ElevenLabs API, returns MP3 audio
- **Sessions**: Per-WebSocket-connection conversation history

## Reliability

### WebSocket Reconnection
- Android client auto-reconnects on failure/close with exponential backoff (1s, 2s, 4s, ... cap 30s)
- Backoff resets on successful connection
- Manual disconnect (`disconnect()`) stops reconnection
- Dot border indicates connection state: white (connected), red (disconnected), yellow pulsing (reconnecting)

### Cancellation
- Client sends `{"type":"cancel"}` to abort in-flight requests
- Backend uses per-session `AbortController` — signals propagate to all API calls (Whisper, OpenRouter, ElevenLabs)
- New audio automatically cancels any previous in-flight request for the same session
- Server responds with `{"type":"cancelled"}` to acknowledge

### API Timeouts
- Whisper STT: 30s
- OpenRouter triage: 15s
- OpenRouter analysis: 30s
- ElevenLabs TTS: 15s
- Pending screenshot requests: 60s TTL

### Error Handling
- `ErrorToastView` overlay shows user-visible error messages near the dot
- Auto-dismisses after 3s (1.5s for "Connected" messages)
- Error sources: connection lost, reconnecting, mic unavailable, send failed, server errors, TTS failure
- TTS failure sends `hasAudio: false` — Android skips audio wait, goes directly to highlights/idle
- Backend validates OpenRouter response fields before accessing

### Graceful Shutdown
- SIGTERM/SIGINT closes all WebSocket connections with code 1001 ("going away")
- Force exits after 5s timeout if connections don't close cleanly

## Implementation Notes
- AccessibilityService requires manual enablement in Settings > Accessibility
- `getRootInActiveWindow()` returns null during screen transitions
- Android devices connect to backend via machine's LAN IP address (localhost resolves to the device itself)
- Screenshot compression uses JPEG quality between 60-80
- Accessibility tree filtering excludes nodes with empty text and content descriptions
- AudioRecord operations run on background thread
- Whisper API requires WAV format with 44-byte header (rejects raw PCM)
