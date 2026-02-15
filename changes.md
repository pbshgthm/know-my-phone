# Changes

## 2026-02-09
- Added session identity and reset flow: `hello`/`reset_session` messages, in-memory session reuse, and `session_status` counts.
- Added screenshot request audio flow (`screenshot_request`) so the model speaks before asking for confirmation.
- Updated prompts with explicit screenshot rubric and highlight selection rules.
- Redesigned floating orb: white circle with shadow, icon-only states, press-and-hold recording, message counter badge.
- Reworked highlight overlay to subtle ring + minimal label pill.
- Simplified main app UI: minimal layout, permission buttons only when missing, Start/Reset + Stop buttons.
- Fixed Kotlin compile issues (self-referential init + non-exhaustive `when`) in `AssistantViewModel`.
- Backend tests run and passing.
- Android unit tests run via Gradle with Android Studio JDK; no unit test sources found (NO-SOURCE).
- Updated orb visuals: lucide icons for mic/loader/audio, listening ring, improved shadow, and badge position.
- Added language dropdown (English, Tamil, Hindi, Kannada, Telugu) and wired language selection to STT/TTS.
- Added optional per-language ElevenLabs voice IDs in `backend/.env.example`.
- Cleaned Android touch listener + coroutine label warnings; Android unit tests run (NO-SOURCE).
- Backend tests re-run and passing after language wiring changes.
- Added lucide icons for screenshot confirm buttons and tightened orb badge position.
- Added explicit STT/TTS language forcing: Whisper `language` + prompt, ElevenLabs `language_code` + per-language model support.
- Tests run: backend `npm test` (pass); Android `testDebugUnitTest` (NO-SOURCE, build OK).
