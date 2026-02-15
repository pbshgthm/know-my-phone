# Get Started with Know My Phone

This guide covers everything you need to run the backend, build or install the Android app, configure environment variables and voice models, understand the data folder, and use the debug UI.

---

## Download the APK

**[Download APK](https://github.com/pbshgthm/know-my-phone/releases)** *(link to be added)*

- **Releases** – Tag a version (e.g. `v1.0`) and the APK will be built and attached to the release.
- **Latest build** – Open [Actions](https://github.com/pbshgthm/know-my-phone/actions), pick the latest successful run, and download the APK from the workflow artifacts.

*(Add your APK download link above once ready.)*

---

## Backend Setup

The backend is deployed on Replit at [https://know-my-phone.replit.app/](https://know-my-phone.replit.app/). You can use it as-is, or run locally for development.

### Local development

1. Install dependencies:
   ```bash
   cd backend
   npm install
   ```

2. Create and edit your env file:
   ```bash
   cp .env.example .env
   # Edit .env with your API keys
   ```

3. Start the server:
   ```bash
   npm run dev   # or: npm start
   ```
   Server runs at `http://localhost:8765`.

---

## Android Setup

### Option A: Install pre-built APK

1. Download the APK from the link above.
2. On your Android device, enable "Install from unknown sources" (or "Allow from this source" for the app you use to open the APK).
3. Open the APK file and install.
4. Configure the app's backend URL in Settings if you're running a local server (see below).

### Option B: Build from source

1. Open the `android` folder in Android Studio.
2. **Port forwarding (required for dev)**: The app connects to the backend over WebSocket. When using a device or emulator, run:
   ```bash
   ./adb-reverse.sh start
   ```
   This forwards `tcp:8765` on the device to your host. Use `./adb-reverse.sh stop` to clear it. Or run manually: `adb reverse tcp:8765 tcp:8765`.
3. Build and run on device or emulator (minSdk 30+).
4. Grant permissions: **Microphone**, **Display over other apps**, and **Accessibility** (enable manually in Settings → Accessibility → Know My Phone).

---

## Environment Variables

All env vars live in `backend/.env`. Copy from `backend/.env.example` and fill in the values.

### Server

| Variable | Description | Example |
|----------|-------------|---------|
| `PORT` | HTTP and WebSocket server port | `8765` |
| `DATA_DIR` | Root directory for persisted conversations. Defaults to `../data` relative to backend. | `/path/to/know-my-phone/data` |

### LLM (AI SDK)

| Variable | Description | Example |
|----------|-------------|---------|
| `LLM_MODEL` | Model in `provider/model` format | `google/gemini-3-flash-preview` |
| `GOOGLE_GENERATIVE_AI_API_KEY` | Required for Google models (Gemini) | `your-key` |
| `ANTHROPIC_API_KEY` | Required for Anthropic models (Claude) | `your-key` |

Set the API key for whichever provider your `LLM_MODEL` uses.

### ElevenLabs (STT & TTS)

| Variable | Description | Example |
|----------|-------------|---------|
| `ELEVENLABS_API_KEY` | ElevenLabs API key | `your-key` |
| `ELEVENLABS_STT_MODEL_ID` | Speech-to-text model | `scribe_v2` |
| `ELEVENLABS_STREAMING_MODEL` | TTS model for streaming | `eleven_v3` |
| `ELEVENLABS_VOICE_ID` | Default voice when language has no override | `iNwc1Lv2YQLywnCvjfn1` |

### Voice IDs by language

Override the default voice per language using `ELEVENLABS_VOICE_ID_<LANG>`:

| Variable | Language |
|----------|----------|
| `ELEVENLABS_VOICE_ID_EN` | English |
| `ELEVENLABS_VOICE_ID_TA` | Tamil |
| `ELEVENLABS_VOICE_ID_HI` | Hindi |
| `ELEVENLABS_VOICE_ID_KN` | Kannada |
| `ELEVENLABS_VOICE_ID_TE` | Telugu |
| `ELEVENLABS_VOICE_ID_ML` | Malayalam |

If a language has no override, `ELEVENLABS_VOICE_ID` is used. Voice IDs come from [ElevenLabs](https://elevenlabs.io/); pick voices that suit each language’s accent and style.

---

## Voice Models

### Speech-to-text (STT)

- **Model**: `ELEVENLABS_STT_MODEL_ID` (default: `scribe_v2`)
- ElevenLabs Scribe supports the Indic languages via `language_code` (e.g. `hin`, `tam`, `kan`, `tel`, `mal`). The app sends the selected language when the user changes it.

### Text-to-speech (TTS)

- **Model**: `ELEVENLABS_STREAMING_MODEL` (default: `eleven_v3`)
- **Voices**: One `ELEVENLABS_VOICE_ID_*` per language. Choose voices that sound natural for that language.
- The backend uses HTTP streaming so TTS audio starts as soon as the first chunk is ready.

---

## Data Folder

Conversations are persisted under `DATA_DIR` (default: `data/` at the project root).

### Structure

```
data/
└── <clientId>/                    # e.g. UUID from device
    └── <sessionId>/               # e.g. UUID per session
        ├── conversation.json     # Metadata + turn list
        ├── audio-input/          # WAV files (user speech)
        ├── audio-output/         # WAV files (assistant speech)
        ├── screenshots/          # JPEG screenshots
        └── ui-trees/             # JSON accessibility trees
```

### conversation.json

Each conversation has:

- `clientId`, `sessionId` – identifiers
- `language` – e.g. `ta`, `hi`, `en`
- `deviceInfo` – manufacturer, model, Android version
- `turns` – array of turns with:
  - `input`: transcript, audio file path
  - `output`: text, audio file path, highlights
  - `triage`: whether a screenshot was needed and why
  - `screenshot`: file paths for screenshots and UI trees
  - `timing`: timestamps for each pipeline stage (see Debug UI below)

The backend creates this structure automatically. Do not delete or move folders while the server is running.

---

## Debug UI

The backend serves a single-page debug UI at `http://localhost:PORT/` (same origin as the API). It displays all persisted conversations—useful for development and demos.

### Access

1. Start the backend (`npm run dev` in `backend/`).
2. Open `http://localhost:8765` (or your `PORT`) in a browser.
3. The UI loads clients and conversations from the data folder.

### Layout

- **Sidebar**:
  - Client dropdown (clients that have stored conversations)
  - List of conversations for the selected client
  - Live indicator when new data appears
- **Main area**:
  - Turn cards: each turn can be expanded or collapsed
  - Full pipeline breakdown
  - User transcript, input/output audio playback
  - Screenshot and UI tree side by side
  - Highlights sent to the client

### How to read it

Each turn card shows the pipeline stages and when they happened:

| Field | Meaning |
|-------|---------|
| `audioReceivedAt` | When the server received the user’s audio |
| `sttStartedAt` / `sttCompletedAt` | STT (speech-to-text) run |
| `triageStartedAt` / `triageCompletedAt` | Triage decided if a screenshot was needed |
| `screenshotRequestedAt` | When the server asked the client for a screenshot |
| `screenshotReceivedAt` | When the client sent the screenshot |
| `llmStartedAt` / `llmFirstTokenAt` / `llmCompletedAt` | LLM inference |
| `ttsFirstAudioAt` | When TTS started streaming audio |
| `completedAt` | When the turn finished |

Use these timestamps to see where time is spent (e.g. slow STT or LLM). The transcript, screenshot, UI tree, and highlights show exactly what the LLM saw and returned.

### URL routing

The UI uses hash-based routing: `#client=X&conv=Y`. You can share links to specific conversations.

### REST API (used by Debug UI)

- `GET /api/clients` – list clients
- `GET /api/clients/:clientId/conversations` – list conversations
- `GET /api/clients/:clientId/conversations/:convId` – full conversation JSON
- `GET /api/data/:clientId/:convId/:type/:filename` – binary assets (screenshots, audio, UI trees)

The conversation list is polled so new turns appear as they complete.

---

## Next steps

- [How it works](./how-it-works.md) – Technical overview, UX flows, architecture
- [README](./README.md) – Project overview and user experience
