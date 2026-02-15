TODO: UX + Session + Prompt Overhaul (Hackathon Demo)

Goal
- Minimal, modern, non-material UI.
- Single in-memory session per client, manually reset.
- Clear UX around "listening / thinking / speaking / screenshot needed".
- High-quality overlays: orb + highlights that look intentional and calm.

Concept Summary
- Voice-first assistant that explains the current phone screen.
- Users do not understand modern UI; we are their tech-support guide.
- Everything should feel calm, trustworthy, and lightweight.

Collaboration Checkpoints (Decisions to Confirm)
- Message counter format on orb: "1/1" vs "1,1" vs single number.
- Orb size: 56dp vs 64dp (I recommend 56dp).
- Highlight style: ring-only vs ring + label pill (I recommend ring + small label if actionable).
- Tap-to-talk alternative: keep strict press-and-hold only, or allow tap toggle as accessibility fallback (I recommend optional fallback).
- Confirmation overlay icon: camera vs hand-tap vs dot pulse (I recommend simple camera icon).

Session + Context
- One session per client, in memory only.
- Manual reset only. No auto-clear.
- Reset clears conversation history immediately.
- Client reconnects should resume the same session identity.
- If reconnect occurs while a request is in flight, cancel and re-sync state.
- Show message counter on orb: number of user and assistant messages in current session.
- Counter updates immediately after user transcript and assistant response are added.

Conversation Flow
- Always speak before requesting screenshot.
- If screenshot needed, the spoken response must explain why in plain language.
- No on-screen text prompt for screenshot; the user only hears the voice instruction.
- Confirmation overlay is icon-only and disappears before capture.
- Delay screenshot capture after overlay is hidden (300ms to 500ms).
- Capture should be blocked if overlay is visible or if UI tree is in transition.

Prompting (Triage)
- Strong rules about when to request screenshot.
- Add explicit rubric for "needsScreenshot" vs "noScreenshot".
- If question references "this screen", "this app", "this button", "where is", or "what do you see", request screenshot.
- If question is general knowledge or about previous steps, no screenshot.
- If user already provided clear textual description, prefer no screenshot.
- Return reason written to be spoken aloud.

Prompting (Analysis + Highlights)
- Highlight rubric:
- Highlight only when it helps a concrete action.
- Prefer 0 to 2 highlights; 3 is a hard maximum.
- If user asked "where is X", highlight only the best candidate.
- Avoid highlighting static or informational elements.
- Avoid highlighting if the answer is descriptive only.
- Label text should be 2 to 4 words, action-oriented.
- Use numeric labels only when multiple highlights are required.

Floating Orb (Overlay) Design Spec
- Shape: white circle with soft shadow, no stroke.
- Size: 56dp (proposed), shadow 12dp blur, 20% black, y offset 4dp.
- Draggable with edge-snapping and safe-area margins.
- Press-and-hold to talk, release to send.
- Only three icon states, no pulsating size changes:
- Listening: microphone icon.
- Thinking/loading: minimal thinking icon (3 dots or small spinner).
- Speaking: simple equalizer bars (3 or 4 bars).
- Optional small badge in top-right for message counter.
- Badge style: tiny circle or rounded pill, subtle gray background, dark text.
- Orb should remain above highlight overlays.

Highlight Overlay Design Spec
- Default highlight: rounded rectangle ring around target bounds.
- Ring thickness: 2dp, color: soft blue or teal, 70% opacity.
- Minimal label pill placed near top-left of the ring.
- Label pill: white background, 70% opacity, dark text, 10dp radius.
- Avoid overlapping the target element if possible.
- If multiple highlights, number them 1, 2 (avoid more than 3).
- Fade in and fade out; no bounce or scale.

Screenshot Confirmation Overlay
- Icon-only confirmation near orb, no text.
- Uses haptic feedback when shown.
- On tap: hide overlay, wait 300ms to 500ms, capture screenshot.
- If user cancels, overlay hides and we resume idle.
- The overlay must never appear in the screenshot.

Main App UI (Non-Overlay)
- Minimal layout, no Material design cues.
- Primary actions:
- Start/Reset Session.
- Stop Agent.
- Permissions:
- Show text buttons for missing permissions only.
- Hide permission buttons once granted.
- No external URLs or extra text.
- Keep typography clean, single font, no heavy headers.

Expert Opinion (Suggested Improvements)
- Add a subtle haptic when listening starts and ends to confirm press-and-hold.
- Add a short confirmation tone before recording starts (optional toggle for privacy).
- Edge-snap on drag improves usability and avoids blocking UI.
- If press-and-hold feels fatiguing, add tap-to-toggle as an accessibility option.
- For highlights, use one strong highlight instead of many weak ones.
- Keep the orb position persistent within the session to reduce user confusion.

Implementation Tasks

Backend
- Enforce single in-memory session per client.
- Add explicit WS message for "reset_session".
- Track message counts (userCount, assistantCount) in session.
- Emit counts to client after each addition.
- Update prompts to include triage rubric and highlight rubric.
- Ensure audio response is sent before need_screenshot.

Android
- Add minimal main screen with two primary actions.
- Add permission buttons only when missing.
- Add reset session action.
- Update overlay orb UI to new spec.
- Add message counter badge to orb.
- Implement press-and-hold recording gesture with haptics.
- Implement icon-only screenshot confirmation overlay.
- Ensure confirmation overlay is hidden before screenshot capture.
- Update highlight rendering to ring + label spec.

QA and Tuning
- Verify screenshots never include overlay UI.
- Verify session resets actually clear history.
- Test reconnection flow and session continuity.
- Validate highlight count never exceeds 3.
- Verify audio always plays before screenshot request.

