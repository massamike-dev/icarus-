# Reference-led dashboard restoration

## Result
The home screen follows the two supplied phone screenshots: centered, gold serif ICARUS branding, an honest intelligence-mode label, “Speak with ICARUS,” one large central assistant, rounded conversation starters, and a gold-outline reply field on near-black navy. The full-body Lil ICARUS replaces the older circular face while preserving the prominence the screenshots give the assistant. Voice, Driving Mode, Chat, Memory, and Settings remain reachable.

The dashboard reply field and starter chips pass drafts into Chat for review. They do not silently submit prompts or execute device actions. Local sessions identify on-device storage and need no account. Cloud sessions retain sign-out; local profile access opens Settings. Native connectivity is checked explicitly, with no fabricated “online” state.

## References inspected
All nine images supplied on October 9 were visually inspected before the final design pass:
- `5009.jpg`: black/gold mythic ICARUS material and wordmark reference.
- `5576(1).png`: approved full-body gold/navy, blue-lit Lil ICARUS with forehead W. The exact RGBA source is copied unchanged as the fallback poster at `web/public/brand/icarus-companion.png`. Its alpha channel is present; no new art or paid generation was used.
- `5346(2).png`: gold/blue winged-helmet icon identity, bundled unchanged at `web/public/brand/icarus-app-icon.png` and used on authentication/sidebar surfaces. Native launcher assets are unchanged.
- `5345(3).png`: driving HUD goal with central visible roadway and route guidance; not a claim about current spatial-navigation functionality.
- Phone screenshots ending `_8187.jpg` and `_8176.jpg`: authoritative conversational home/chat hierarchy and controls.
- `4935.png`: alternate celestial brand atmosphere.
- `4554(1).png` and `4538.png`: Wastelands game art; not substituted for the assistant mascot or used as dashboard content.

Prior text context also requires Lil ICARUS to be a functional focal point and Driving Mode to preserve route visibility. Duplicate “Ready” labels and the earlier generic orbital W are not restored.

## Implementation
- `web/src/dashboard.jsx`, `dashboard.css`: reference-led home and responsive variants.
- `main.jsx`: mode-aware shell, draft handoff, local privacy routing, accessible navigation state, storage-error feedback in Memory.
- `icarus-companion.jsx`: inline dashboard variant, unchanged native actions and state-driven animation, canonical crest as last-resort fallback.
- `lil-icarus-3d.jsx`: catches renderer initialization failures so a missing WebGL implementation cannot blank the dashboard.
- `styles.css`: removes remotely loaded Google Fonts for offline use.
- `DESIGN.md`: shared palette, font, layout, and behavior intent.

## Verification
Five new dashboard DOM/contract tests cover central assistant and retained navigation, starter draft handoff without auto-send, typed draft handoff, consumed-draft clearing, honest browser connection feedback, and responsive/reduced-motion/asset source contracts. Existing companion/navigation/HUD tests retain their behavior; two expected labels/fallback selectors were updated to reflect the actual UI.

Production Vite build passes. The combined web suite passed 281/281 tests, including the five new dashboard tests. Native checks are listed in the combined verification report.

## Remaining validation
Runtime screenshot capture was attempted with local Chromium and Playwright. Chromium failed at process startup because the execution environment prohibits its process-singleton socket, on both available attempts. No runtime screenshot or pixel-perfect claim is made. Narrow/desktop CSS and DOM behavior are implemented and tested, but final real-browser screenshots, real-device layout, actual GLB appearance, touch targets, and headset/road visibility must still be inspected. No APK/device or driving release-readiness claim follows from these web tests.

No deployment, push, merge, external upload, account permission change, or paid asset generation was performed.

## Graphics fidelity and verified asset blocker
The user's references set the rendering-quality target, not just color and layout: detailed metallic-gold materials, rich navy structure, crisp blue illumination and polished full-body proportions. The original 1024×1536 RGBA character artwork is used unchanged as the static fallback, retaining its detail and transparency. It is not represented as an animated model.

The current GLB has a verified source defect. GitHub commit `3402a416cd787f2e14f33efa587ec027bb6a99cf` returns blob `005f3bd3e20775c8f8b6dd2882d886af4aa51331` for `web/public/models/lil-icarus/lil-icarus-web.glb`. Independent decoding and Git-blob hashing reproduced that SHA exactly, and the bytes equal the local file. The asset is 786,444 bytes, although its GLB header declares 1,288,580 bytes. The base-color texture visibly decodes to corruption in its lower portion; the metallic/roughness image does not decode. This is not a complete usable rig export. The existing file is preserved without alteration.

Metadata describes one skinned mesh, approximately 40,141 triangles and 1024px base-color/normal textures. Higher-fidelity shading cannot be approved from metadata, and changing lights or colors would not repair the missing bytes. Recovering the original complete Meshy/Blender GLB is a release blocker for the animated companion. The app now catches WebGL startup failures and uses the supplied artwork gracefully; the dashboard labels that fallback “Artwork preview · 3D unavailable.” Live GPU and phone rendering still need comparison against the references once the complete model is restored.

## October 13 visual-fidelity pass

The conflicting two-stage dashboard CSS has been replaced with one coherent
reference-led layout. The empty mobile header gap is removed, the unchanged
full-body companion artwork is enlarged, and the mobile navigation again has
Home, Chat, Memory and Settings. Memory uses a brain-outline icon. Driving,
Voice and device status remain available through an expandable home section;
desktop navigation still exposes all six destinations. A working clear button
resets and refocuses the composer without submitting.

The updated web suite passes 283 tests. The delivered side-by-side comparison
is a **static SVG-derived design/layout preview**, not a browser or Android
screenshot. Its geometry follows the revised CSS, with approximate typography;
actual runtime layout, TalkBack, touch behavior and final visual parity still
need device/browser validation.

The repeated driving reference `6291.png` is byte-identical to `5345(3).png`.
See `DRIVING_GRAPHICS_RESTORATION.md` for the separate native HUD restoration.
