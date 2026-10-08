# AI Auto Agent V4

A development/testing Android agent loop combining Accessibility, MediaProjection screenshots, Vision AI, strict JSON actions, safety checks, cancellation-aware OkHttp, and an emergency floating STOP button.

## Architecture
Screen -> MediaProjection -> aspect-ratio preserving JPEG (max width 720, quality 75) -> VisionApiAdapter -> strict AgentAction JSON -> Safety Validator -> Accessibility Gesture/Global Action -> 1s settle -> next screen.

## API adapters
- `OpenAICompatibleVisionAdapter`: `/v1/chat/completions` style request with `data:image/jpeg;base64,...`. Suitable for OpenAI-compatible gateways and compatible servers that support multimodal chat payloads.
- `GeminiNativeVisionAdapter`: native `generateContent` request using `contents -> parts -> inline_data`.
- `VisionApiAdapter` keeps the networking layer swappable for other providers/local endpoints.

## Cancellation
OkHttp calls are wrapped in `suspendCancellableCoroutine` and `invokeOnCancellation { call.cancel() }`. The agent loop also uses a 12-second `withTimeoutOrNull`. STOP cancels the coroutine Job, so a pending HTTP call is cancelled and no delayed action is executed after cancellation.

## Coordinates
Vision models receive/return normalized 0-1000 coordinates. The executor maps those values directly to the current physical display dimensions. The screenshot downscale never changes the normalized coordinate system.

## Safety
Maximum 15 agent steps. Example sensitive packages are blocked: PhonePe, Google Pay/Wallet variants, Paytm, and Android Settings. This is a development safeguard, not a guarantee of safety. Review/extend the list for your own testing.

## Setup
1. Build/install the APK on a test device.
2. Enable the Accessibility Service.
3. Grant overlay permission for the floating STOP button.
4. Grant MediaProjection screen-capture permission.
5. Enter endpoint, model, and API key. The key is stored in app SharedPreferences for development convenience; it is not a secure secret store and can be extracted from a client app. For production use a backend proxy or stronger credential design.
6. Enter a goal and START AGENT.

## Important Android notes
MediaProjection is user-consent based and must run through a foreground service. Accessibility automation and overlay usage can be restricted by Android/OEM policy. Play Store distribution also requires compliance with Google's Accessibility API policies.

## Known provider differences
OpenAI-compatible servers vary in support for JSON Schema response formatting and multimodal fields. Gemini Native uses its own payload. If a provider does not support `response_format=json_schema`, use an adapter tailored to that provider.

## V4.1 runtime hardening
- MainActivity has an onResume preflight gate for Overlay, Accessibility, and MediaProjection.
- Start Agent remains disabled until all three checks are green.
- Vision responses are sanitized before JSON parsing to tolerate markdown fences or surrounding text.
- TYPE waits ~300ms before a global BACK to dismiss the soft keyboard, then the agent applies its normal settling delay.
- Logcat uses one tag: `AI_AGENT`. Filter with `tag:AI_AGENT`.
- Floating overlay creation is guarded by `Settings.canDrawOverlays()` and catches `BadTokenException`.

This ZIP is source-deliverable; an Android SDK/Gradle environment is still required for a real device build verification.

## V4.2 Game Mode

V4.2 adds an explicit Pure Vision Game Mode for a configured game package. When the active package matches `gamePackage`, the accessibility-tree shortcut is bypassed and the agent uses screenshot + Vision only. Game mode uses a 10-step cap and a 1.5–2.0 second settle delay after gestures.

### Rotation-safe capture

`ScreenCaptureService` reads the current display rotation and physical display metrics. If rotation changes, the old `ImageReader` listener is nulled before the reader is closed, the old `VirtualDisplay` is released, and both are recreated at the new width/height. A 150 ms warm-up period rejects early frames. Null/black frames are discarded.

### Game safety

Vision actions contain `target_label` and `is_safe`. A deterministic safety guard rejects purchase/store/currency/unlock wording before gesture dispatch. Rejection is logged as `Action rejected by safety guard` and the agent stops rather than blindly clicking.

### Testing

For the first Aniimo benchmark, manually open the game and navigate to the daily reward screen. Connect USB, enable Android Studio Logcat with filter `tag:AI_AGENT`, then start the agent from the floating STOP overlay. Configure the exact game package in MainActivity so Pure Vision Mode activates.

This project has not been compiled in the generation environment because no Gradle wrapper/system Gradle or Android SDK was available there. Build verification must be performed in Android Studio on a machine with the Android SDK installed.
