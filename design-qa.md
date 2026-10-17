# Blue-glass theme visual verification

**final result: passed**

## Visual truth and browser evidence

- Source visual truth: [approved softer-gold concept](docs/ui-qa/approved-soft-gold.webp), approved October 17, 2026. The original is 841×1870 pixels; this WebP copy preserves its dimensions.
- Implementation: actual headless Chromium screenshots of Vite-built offline assets, with all non-fixture network requests aborted. Source commit `05c1c84c8e0cb054657b02d23616c49da2c1a896`; [browser run](https://github.com/massamike-dev/icarus-/actions/runs/39066683382).
- Viewports: 320×568, 393×852, 430×932, 1440×1000 CSS pixels, deviceScaleFactor 1. Screenshots are therefore 1:1 CSS pixel captures.
- State: Home in on-device mode with explicit simulated native waiting/listening responses; supporting screens use sample local data. No screenshot establishes real device or account health.
- [Full-view comparison](docs/ui-qa/comparison-home.webp): source app-owned crop `(56,181)-(786,1719)`, excluding the surrounding caption/device chrome, scaled uniformly from 730px to 393px wide; implementation 393×852. Both appear together in one comparison image. The illustration is not a measurable device runtime, so its remaining height is 828px after scaling, rather than forcing a distorted 852px crop.
- Focused evidence: [Chat composer](docs/ui-qa/chat-composer.webp), [Settings voice controls](docs/ui-qa/settings-voice-controls.webp), [Memory](docs/ui-qa/memory.webp), [native consent dialog](docs/ui-qa/voice-consent.webp). Text, controls, disabled states, focus and scrolling were inspected at full resolution as well as in the comparison.

## Findings and iteration history

1. **Resolved P1: clipped Home figure.** Initial source `4e11128` rendered an oversized gold character inside a fixed scene, cutting off its body. The poster now has absolute, bounded scene dimensions and object-fit containment. Source `a52a42f` screenshots show the entire character at every tested size. Browser assertions require its image bounds to remain inside the figure.
2. **Resolved P2: oversized mobile Vehicle actions.** The new horizontal action-group flex basis also applied to existing column-oriented Vehicle controls, producing roughly 160px-tall buttons. The scoped Vehicle override now uses `flex: 0 1 auto`, retaining its 48px minimum. Source `05c1c84` browser checks require actual rendered button heights from 44px to under 120px. The [final action screenshot](docs/ui-qa/vehicle-actions.webp), captured at source `c7efbb0` in [run 39067036560](https://github.com/massamike-dev/icarus-/actions/runs/39067036560), was inspected and shows compact, clearly disabled controls without excess height.
3. **Resolved P2: floating mobile companion covered forms.** The previous transparent 96×112px hit target obscured Settings actions and Chat's composer. Supporting pages now have an opaque 64px voice dock above the existing navigation, plus scroll clearance. The Chat/Settings focused images show controls fully accessible above it. Home keeps its inline scene; desktop keeps the existing corner companion. Open/Close, Escape focus restoration, hide/show, side preferences and typing tuck retain their handlers.
4. **Resolved functional review findings:** Home's inline scene now remains live while typing, and acknowledgement feedback is separate from cancellation/error instructions. Targeted regression tests exercise both.

## Required fidelity surfaces

- **Typography:** Gold tracked serif wordmark and serif Home title match the visual hierarchy; Georgia/system-serif and system sans-serif are offline-safe. Supporting pages share the hierarchy. Long copy wraps without horizontal clipping; minimum target sizes and readable body copy take precedence over compressing the mockup's entire contents into one phone height.
- **Spacing/layout:** Central figure, full-width Talk now, review composer, two-column ideas, full-width troubleshooting and four mobile tabs preserve the approved structure. Mobile scrolls to the controls disclosure, including at 320px. Actual voice status and accessible touch targets add height relative to the illustration; this is intentional functional space, not missing content. Desktop uses the existing sidebar.
- **Color/tokens:** Rich blue backgrounds and glass surfaces, softened cyan borders, restrained gold accents and semantic error/disabled states are shared across pages. Native dialogs remain in the top layer, above navigation and the companion.
- **Assets:** Separately generated background, platform and full-body gold character are sharp, packaged and transparent where needed. No screenshot is used as interactive UI; all editable copy, navigation and controls are real elements. Standard line icons come from Lucide. The operating system owns device chrome.
- **Copy/content:** Home matches the approved core labels. Device/voice availability is derived from native state; local/cloud mode labels are not represented as proof of microphone or model readiness. Ideas transfer drafts without submission. The versioned release panel describes the new on-device theme.

## Interaction and test evidence

- Source `05c1c84`: 19 browser screenshots, 21 named checks, zero page errors. Source `c7efbb0` reran the final versioned UI successfully with 20 screenshots, including Vehicle actions; revised Home and desktop Settings were re-inspected. Additional geometry assertions cover full character bounds, compact Vehicle buttons and mobile dock size/clearance.
- Exercised Home-to-Chat draft transfer with no send, sample Memory save, companion disclosure/Escape/focus restoration, voice consent Cancel with no command, Talk now single dispatch and capture lockout, four navigation tabs, Home typing/clear, no-device disabled voice, reduced motion and horizontal overflow at all sizes.
- Final local suites: 298 web tests, 54 native source-contract tests, 11 preview-verifier tests, 12 private-delivery boundary tests; Vite build passes with the pre-existing large-bundle warning.
- Independent scoped code review found no remaining P0/P1/P2 issue. Browser verification is required by the preview workflow before signing.

## Remaining limits and P3 polish

- Physical Android microphone/recognizer/TTS, soft keyboard/IME behavior, installation and XREAL hardware are not verified by Chromium fixtures.
- Live cloud authentication/integrations were not exercised. The independently hosted cloud site is not deployed by this test release.
- P3: generated background has slightly more luminous points than the concept; subtle platform/character proportion and reflection refinements remain optional.
- P3: the existing desktop corner companion and large 3D application bundle remain candidates for later performance/layout refinement.

## Implementation checklist

- [x] Approved artwork and coherent real controls implemented
- [x] Code, behavior, responsive and accessibility regressions tested
- [x] Iterated screenshots after figure, dock and layout fixes
- [x] Inspect final compact Vehicle action screenshot
- Release stage: bind the signed APK to exact source, checksum, signer, version and permissions in its separate verification report.
