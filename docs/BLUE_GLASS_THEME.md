# ICARUS blue-glass test theme

The October 17 approved softer-gold visual concept is implemented as real controls over separately packaged decorative assets. Home, Chat, Memory, Settings, Voice, Vehicle and associated review/integration panels share the palette. The Android operating system still owns status bars, cutouts and keyboard insets.

## Behavior and scope

- Home's Talk now uses the same native status/permission/busy gate as the companion panel. Opening the page or artwork never starts listening.
- Home message and idea cards open drafts in Chat for review; they do not submit requests.
- Unsupported or unconfirmed native states remain explicit. The on-device badge identifies workspace mode, not a verified microphone or model.
- Mobile keeps four primary navigation items, with Vehicle/Voice accessible under the controls disclosure. The screen scrolls above the fixed navigation.
- Home uses the approved dimensional character artwork. The pre-existing animated 3D companion is retained on supporting pages, with an image fallback.
- No backend configuration, billing, permissions, native voice cancellation policy, production deployment, main merge or Play publication is changed.

The bundled APK contains these screens for on-device mode. Switching to the independently hosted cloud workspace loads the currently deployed website and may show its earlier theme until a separately approved web deployment.

## Assets

`web/public/brand/lil-icarus-soft-gold.webp`, `icarus-blue-glass.webp`, and `icarus-gold-platform.webp` are individually generated from the approved concept with OpenAI image generation. They contain character/background/platform art only, with no baked interface controls or device chrome. The character retains the existing forehead W mark. WebP conversion reduces package size and preserves transparency for character/platform. Standard controls use Lucide React 0.468.0 under its ISC license.

## Verification

`npm test` covers the functional UI and CSS contracts. `node scripts/visual-smoke.mjs` runs against the exact Vite-built assets in Chromium at 320×568, 393×852, 430×932 and 1440×1000. It supplies explicit simulated native responses and local sample memory; it never contacts a live account or physical device. Its screenshots and report are CI artifacts. These are browser evidence, not physical Android microphone, keyboard, recognizer/TTS or XREAL verification.

A commit marked `[visual-qa]` is source-only review: the test-release workflow skips its signing/build/publication job. A subsequent unmarked, version-bumped commit can use the existing draft-PR and immutable-prerelease gates after screenshot review.
