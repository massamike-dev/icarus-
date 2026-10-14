# Canonical ICARUS Android app

This directory is the authoritative Android application for ICARUS.

- Package: `com.icarusalmighty.app`
- Current development line: `1.4.4` / versionCode `21`
- App module: `app/`
- Optional XREAL module: `xreal/`
- Canonical CI/release workflow: `.github/workflows/android-build.yml`
- Published independent ICARUS host: `https://icarusassistant.com`

Do not make Android product changes in the repository-root `/app` directory. That tree is the deprecated 1.2.0 bridge retained only for historical comparison.

## Core architecture

Phone Driving Mode is always available independently of accessories:

```text
Driving Mode
├── Map + foreground GPS + ICARUS voice
├── OBD-II telemetry (optional)
├── XREAL spatial HUD (optional)
└── Meta wearable features (optional)
```

Disabling Meta or XREAL must prevent that integration from initializing or being invoked. A failed/disconnected wearable falls back to the phone experience rather than interrupting navigation or ICARUS voice.

## Implemented native host capabilities

- bundled offline WebView host with the exact-origin `ICARUS_NATIVE_CHANNEL` bridge
- account-free wake-word engine and foreground microphone service
- native command capture and Android TTS
- installed-app launch, flashlight, volume, brightness, battery, alarms/timers
- calls/SMS/contact resolution with sensitive-action policy
- navigation intents and phone Driving Mode GPS bridge
- Bluetooth device reporting
- read-only ELM327-style OBD-II telemetry
- local Gemma model boundary
- signed Play AAB/shareable APK and in-place updater
- optional XREAL module boundary
- Meta DAT boundary, currently crash-guarded pending device validation

## Build

Java 17, Android SDK 36, Node 22+ and npm are required. The APK bundles the web UI; a running web server, account, API key and AI subscription are not needed for local mode.

```bash
(cd ../../web && npm ci)
bash scripts/prepare-sherpa-kws.sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  -PICARUS_WEB_URL="https://icarusassistant.com"
```

Release builds and signing should run through the canonical GitHub Actions workflow rather than local ad-hoc signing.

## Local-first runtime

Fresh installs and upgrades without an explicitly selected runtime start in **local** mode. Gradle's `buildBundledWeb` task builds `web/` with a relative Vite base; `bundleWebAssets` copies only `web/dist` into generated APK assets. `preBuild` depends on this pipeline for every variant. Generated files remain build outputs, not checked-in copies.

The bundled interface loads from `https://appassets.androidplatform.net/assets/web/index.html` through `WebViewAssetLoader`. Public `/brand/` and `/models/` URLs are mapped to bundled assets too. Missing assets return a local 404 instead of reaching the network. Local WebView requests to other origins are blocked; user-initiated external links open an external app. File/content URL access and mixed content stay disabled.

Native status includes `runtime: { mode, localUrl, cloudUrl, cloudAvailable, localCoreFree, automaticCloudFallback }`. `get_runtime_mode` reads this object; `set_runtime_mode` accepts `{mode:"local"}` or `{mode:"cloud"}` and navigates to the selected interface. Cloud selection requires a configured valid HTTPS origin and is persisted explicitly. It clears the prior voice session and cancels an active voice turn. Cloud failure displays a retry/use-on-device choice and never silently retries a prompt elsewhere.

Both origins are eligible for the WebMessageListener, but a request is accepted only from the active exact origin and main frame. Each result is bound to its originating page generation; navigation or a mode switch drops stale results, including memory-bearing local model answers.

### On-device model

Direct native commands, settings, the offline shell and local history do not require a model download. Flexible on-device interpretation and Chat require the optional 2.58 GB Gemma model. Download is explicit, Wi-Fi-only by default, and verified against the pinned size and SHA-256 before loading. Status covers missing/downloading/paused/verifying/ready/failed/unsupported states. The runtime checks ARM64, device RAM and available storage; a compatible 64-bit ARM device with at least 4 GiB physical RAM is required by the guard (6 GiB or more recommended). These are guardrails, not a hardware performance certification. No model was downloaded or device performance verified as part of the source change.

One process-wide inference engine prevents simultaneous model loads. Requests are serialized by a busy gate and get a fresh conversation, closed after each response. `local_chat` accepts `prompt`, optional bounded `messages` (`user`/`assistant` only), and optional `memoryContext`; no prior conversation is implicitly retained. The caller is responsible for selecting the intended chat context. There is no automatic cloud fallback. Inference uses a 110-second coroutine timeout, inside the hands-free 120-second and web 180-second deadlines. Flow cancellation closes the conversation and releases the busy gate; a vendor-native initialization/inference call that does not cooperate with cancellation may still require restarting the app. The busy/timeout messages disclose this recovery path; cancellation behavior must be verified on a target device.

Flexible hands-free interpretation defaults to the local model and feeds the existing allow-list, argument checks, confirmation prompts and device-action handlers. The deterministic `HandsFreeCommands` parser is unchanged and runs first. Model-origin mutating commands also require spoken confirmation (including flashlight/volume), while deterministic shortcuts keep their existing behavior. Hands-free local commands do not read the bundled Chat history or saved memory. Cloud interpretation and result reporting run only when cloud mode is explicitly selected and a valid cloud session exists.

Wake-word detection is local. Android speech recognition/TTS are separate system services: fully offline speech depends on installed recognizer and voice data, and selected services may use their own network access. Do not describe all speech as offline merely because model inference is local.

### Verification

`LocalCommandResultTest` covers allow-list normalization, incomplete/out-of-range arguments, model-supplied authorization rejection, malformed/multiple responses, phone-number preservation and fresh explicit prompt context. `RuntimeModeTest` covers local defaults and origin isolation. Run these with the normal Android unit-test task. The lightweight source-contract checks can run without an SDK:

```bash
node --test scripts/local-runtime-contract.test.mjs
```

Before distribution, run Android compile/unit tests/lint and device tests for airplane-mode cold start, model absence/download/pause/corruption, low RAM/storage, local history isolation, mid-inference mode switching, denied/revoked permissions, and confirmed calls/navigation/timers. Source-contract checks do not substitute for compilation or hardware validation.

## Private phone testing

The `privateTest` build type produces **ICARUS Test** (`com.icarusalmighty.app.test`, version suffix `-test`). It installs alongside regular ICARUS with separate Android storage, WebView cookies, native preferences, and permissions. It accepts `icarus-test://` links; regular `icarus://` links continue to open the regular app.

Use the private test workflow with an authenticated, isolated test backend. The build requires `-PICARUS_TEST_WEB_URL=https://<private-test-origin>` and uses the existing upload signing credentials. There is no production URL fallback. Production origins, URL credentials, non-root paths, queries, and fragments are rejected. Packaging also rejects reserved placeholder hosts and missing signing credentials. CI can compile, lint, and run unit tests with `https://icarus-test.invalid` without producing an installable APK.

Public update checks and Google Play billing are disabled in ICARUS Test. Updates must be installed through the private distribution flow. Production Play subscription, licensing, and update behavior require the normal Play package and cannot be verified with this separate package. Meta DAT remains guarded as in the normal app; future Meta device testing requires approval for the test package and signing identity. Shared external hardware (microphone, Bluetooth, glasses) is still a physical resource: stop the regular app's hands-free listener before testing the second app.

The private origin receives the same exact-origin, main-frame-only Android bridge restrictions as production. The APK contains no access credentials: the test server must enforce account access and keep its test data separate.

## Permissions

See the repository-root [`PRIVACY.md`](../../PRIVACY.md). Location is foreground-only for Driving Mode; ICARUS does not request Android background-location permission.

## Wake-word release check

The wake service is intentionally opt-in and user-visible. The detection threshold still requires real-device calibration for false accepts and missed detections before general release.

## Vehicle safety

The OBD implementation is read-only. It sends standard diagnostic PID queries and adapter setup commands only. It contains no ECU write/reflash/control commands.
