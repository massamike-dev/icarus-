# Local-first ICARUS core

Status: source implementation; not a signed APK or a verified device release.

## Product boundary

The installed Android app defaults to the bundled on-device workspace. Opening
its dashboard, viewing saved local chats and managing explicitly saved Memory
requires neither a server account nor per-message AI payment. AI chat and
flexible language interpretation require the one-time Gemma model download
(approximately 2.6 GB) and a supported phone. Downloading is user initiated.
Basic deterministic phone commands remain independent of the AI model.

Cloud is a separate, explicit choice. The mode review explains internet,
provider processing, sign-in and possible charges. Switching does not upload
local history or Memory or purchase a subscription. A failed local inference
never starts a cloud request. The existing hosted account, reviewed actions,
read-only integrations and optional billing controls remain available in cloud
mode rather than blocking the local core.

## Architecture

- Gradle builds the web application and copies its output into generated Android
  assets; Node 22+ and `npm ci --include=dev` in `web` are build prerequisites.
- Android serves the bundled page from its appassets HTTPS origin. Privileged
  bridge messages are scoped to the active trusted main-frame origin. File URL
  access is not used to expose the bridge.
- Local web APIs use device WebView storage for saved conversations and Memory.
  Native local inference receives bounded context; temporary chats exclude saved
  Memory and are not persisted. Native inference conversations are isolated.
- Model interpretation produces proposals, never direct model-initiated device
  execution. Existing argument validation, review UI and Android action handlers
  own execution. Hands-free deterministic parsing and confirmation rules remain.
- Local hands-free interpretation handles supported device commands. It does not
  read the web workspace’s saved local Chat or Memory; local conversational AI
  is available through the Chat screen.
- Cloud account storage and on-device storage are separate. They are not synced.
  Clearing app data or uninstalling removes local history and Memory; users should
  not treat this version as a backed-up archive. Android backup remains disabled.
- Android speech recognition may need internet depending on installed engines.
  Maps, provider integrations, cloud AI and model downloads have their own network
  requirements. “Local-first” does not claim every phone feature works offline.

## Acceptance checks before release

1. Run Android unit tests, lint and build in the configured SDK/Gradle CI.
2. Install a signed test APK on representative arm64 Android phones; airplane-mode
   cold start must render dashboard, Settings, existing Chat and Memory.
3. Test missing model, interrupted/paused download, low storage, checksum mismatch,
   unsupported runtime, failed inference and repeated sends. None may contact cloud.
4. Download on a permitted test connection and verify actual model loading, CPU/GPU
   fallback, memory pressure, response quality and useful latency. No model download
   or actual inference was performed during source-only verification.
5. Verify independent chat contexts and temporary mode; navigate away mid-request,
   retry failed turns, delete history, and restart the app without duplicates.
6. Exercise every reviewed phone action and hands-free confirmation, including locked
   screen, denied permission, cancellation and unknown outcomes. An accepted Android
   request must never be presented as proof a call connected or an action finished.
7. Switch deliberately to cloud, sign in, use connected services, then return local.
   Local data must stay on-device; local failure must not silently switch providers.
8. Validate narrow/mobile/landscape layout, touch targets, keyboard, TalkBack,
   reduced motion, companion fallback and actual glasses/Driving Mode as applicable.

This package does not change credentials, permissions, signing identity, production
hosting, Play listings or prices. No push, merge, deployment or device install is
part of the source deliverable.
