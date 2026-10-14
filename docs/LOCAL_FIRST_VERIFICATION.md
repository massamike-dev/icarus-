# ICARUS local-first source preview: verification

Date: 2026-10-09
Target repository base: `3402a416cd787f2e14f33efa587ec027bb6a99cf`

The patch is prepared from a reconstructed local source copy. Use a complete
checkout of the target base; this package is a changed-file overlay and patch,
not a complete repository export.

This is an uncommitted source preview, not an install-ready Android release. It
combines the earlier assistant/context and read-only integration work with the
local-first core and reference-led dashboard restoration. No push, merge, hosted
deployment, signing operation or device install was performed.

## Checks performed

Final executable checks: **281/281 web tests**, **11/11 native/web source
contracts**, **12/12 private-configuration tests**, Vite relative-base production
build, and **0 strict UI audit findings**. Logs are in `verification/`. Vite
reports a non-failing large-chunk warning (955.48 kB JavaScript before gzip).
These checks cover:

- The complete Node web/server suite, including existing cloud behavior,
  integrations, Chat context, billing controls, device reviews and HUD controls
- New local storage, duplicate/retry handling, delete semantics, temporary
  isolation, no-network local APIs, missing-model handling and browser-DOM flows
- Account-free bundled startup and explicit review before cloud mode selection
- Dashboard routes, suggestion-to-draft behavior (no automatic send), mascot
  fallback, motion/accessibility source contracts and memory error feedback
- 11 native/web source-contract checks (not Android runtime execution)
- 12 private-delivery configuration checks
- Vite production builds, relative-base packaged asset output, workflow YAML
  parsing, and whitespace/diff checks

## Review corrections

Independent source review found and the implementation corrected:

- Timer argument naming differences between Android and the web client
- Deleted conversation text surviving in retry tombstones
- Late model replies, transcripts, status and pending voice actions crossing a
  mode or page change
- Missing verifying/unsupported model setup states
- Cooperative inference timeout and cleanup; a stuck vendor JNI call can still
  require restarting the app
- Mutating model-origin voice proposals now always require spoken confirmation;
  deterministic direct shortcuts retain their existing behavior

No unresolved blocking source findings remained within the reviewed scope.

## Baseline binary integrity

The `web/public/models/lil-icarus/lil-icarus-web.glb` present at the pinned
repository revision has 786,444 bytes against a GLB header length of 1,288,580.
Its bytes were matched to the authoritative repository blob hash during the
asset review; the truncated model cannot establish the requested 3D quality.
The local old `icarus-floating-head.png` also does not decode, but upstream
integrity of that separate unused image was not verified.

Neither inherited binary is changed or included in this overlay. The supplied
high-resolution companion PNG decodes at 1024 × 1536 and provides an explicitly
labelled artwork fallback while 3D is unavailable. All 11 native WebP assets
decode, and the walking GLB header length matches its local bytes. A production
Vite build does not validate 3D asset integrity or appearance. Recover and verify
the complete approved 3D model before claiming animated visual parity.

## Not verified here

- Android compilation, Gradle JUnit execution, lint, APK/AAB packaging or signing:
  this workspace has Java but no Android SDK, Gradle or Kotlin toolchain
- Real model download, checksum of downloaded bytes, GPU/CPU inference quality,
  memory consumption, speed, thermal behavior or JNI cancellation on a phone
- Actual airplane-mode WebView asset loading, touch layout, TalkBack, wake-word,
  speech recognition/TTS, calls, timers, navigation, Bluetooth, vehicle or glasses
- Pixel/screenshot browser QA: browser runtime access was blocked; DOM/static
  checks and inspection of the supplied reference images are not visual proof
- Provider OAuth/live integration accounts, production APIs, payments or billing

New Android JUnit tests are supplied for command validation, prompt isolation,
runtime defaults, mode separation and model-origin action confirmation. They
must run in the configured Android build environment before release. Follow
`docs/LOCAL_FIRST_CORE.md` and `docs/DASHBOARD_RESTORATION.md` for device and visual
acceptance steps. The private APK workflow still requires its valid separate
restricted service/signing configuration; none was invented or changed here.
