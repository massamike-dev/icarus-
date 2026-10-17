# ICARUS interface

## Scope and visual sources
The Android-first assistant is a working command surface, not a marketing page.
The current restoration follows the user's request to recover the discussed aesthetic.
The established mascot is full-body Lil ICARUS, with gold/navy armor and blue light. The approved transparent artwork is the current verified fallback because the inherited rigged GLB is truncated. The companion retains listening, thinking, speaking and action-state controls; the fallback is not described as a working animated rig.
Driving Mode remains a separate safety-oriented surface: roadway visibility, restrained motion, real navigation and no invented vehicle readings.

## Runtime ownership
`web/src/dashboard.css` owns shared visual token overrides and command-dashboard presentation. Existing feature styles retain their functional layouts. `styles.css` owns foundational controls; `companion.css` owns the floating assistant; the dashboard-specific companion variant is explicitly scoped in `dashboard.css`.
- Ink: `#060b18`
- Panel: `#0c1424`
- Raised panel: `#142034`
- Line: `#2b3042`
- Secondary text: `#b3b0a4`
- Main text: `#f0efeb`
- Blue light: `#55b9ed`
- Gold structure / primary action: `#d4bb79`
- Focus: `#f2d99c`

Use local system font stacks; no network font dependency for the offline shell. Display type uses Georgia with a local serif fallback; body typography uses system UI. Use gold, widely tracked serif wordmarks and a calm serif “Speak with ICARUS” heading, matching the supplied phone screenshots. Body copy remains system sans-serif.

## Layout and interactions
The home screen gives Lil ICARUS a central stage, immediate chat/voice entry, and real links to Driving Mode, Voice and Memory. There is one companion instance per screen. Other screens retain the corner companion. All six destinations remain accessible. The mobile bar follows the original four-tab layout: Home, Chat, Memory and Settings. Driving Mode and Voice remain in the home screen’s expandable controls and in the desktop navigation. The safe-area-aware mobile bar stays clear of the main document and companion.

Do not claim native connection, wake listening, telemetry, or AI availability without real state. The connection button performs the existing read-only device check. Account and local-storage labels differ honestly. Primary actions use gold; danger and warning actions retain their semantic colors.

## Accessibility and validation
Buttons have accessible labels and visible focus; controls target at least 44 CSS pixels. Reduced motion applies globally. The mascot panel is a non-modal disclosure with Escape and focus restoration. No new external permissions or phone actions run from simply opening the dashboard.

Layout verification must cover 320/390px phones, tablet and desktop, long names, disconnected native controls, failed GLB/WebGL, hidden mascot, keyboard panel dismissal and reduced motion. Runtime screenshot QA is required before claiming visual sign-off; source and DOM checks alone are insufficient.

## User-supplied reference reconciliation (October 9, 2026)
All nine provided images were inspected as pixels. The two phone UI images establish centered ICARUS branding, mode status, the Speak with ICARUS heading, one central character, rounded conversation-starter chips, and a gold-outlined reply field. The full-body gold/navy mascot PNG is the actual source for the transparent fallback poster, copied unchanged as `web/public/brand/icarus-companion.png`; the rigged GLB remains the primary rendering. The new full-body companion supersedes the old circular face but preserves that visual prominence. Duplicate Ready labels are not restored.

The driving-HUD reference stays separate from the app dashboard: do not imply that a road-aligned ribbon or vehicle readings exist because the reference illustrates them. Wastelands game art informs material/color context only; it is not inserted into the assistant or mistaken for its mascot.

The reply field and suggestions transfer drafts to Chat for review, without sending or triggering any phone action. All six existing navigation destinations remain functional; the mobile bar now matches the historical four-tab layout.

The supplied 1254px gold-and-blue winged-helmet icon is bundled unchanged as `web/public/brand/icarus-app-icon.png` for authentication and sidebar identity. Preserve its detailed metallic material; do not substitute a flat illustrative logo.


## Approved blue-glass theme (October 17, 2026)

This supersedes the earlier dashboard color/layout guidance above. The approved softer-gold concept uses rich blue translucent glass, subdued cyan edging, dimensional gold Lil ICARUS and blue primary buttons with gold/cyan highlights. Current tokens are defined in dashboard.css: ink #021027, panel #07244a, raised #0d3565, line #417eac, secondary #c0d8f2, text #f5f8ff, cyan #73d5ff, gold #f1d17d, focus #ffe4a1.

Home uses separate generated character, platform and background WebP assets, while its typography, buttons, drafts and navigation remain native DOM. Supporting pages use glass-pages.css and preserve their original functional layouts. On mobile the supporting-page animated companion is contained in a reserved 64px dock above navigation; desktop retains the corner figure. Native OS status bars and keyboard remain host-owned.

Browser screenshots and flow checks use explicit fixtures. See docs/BLUE_GLASS_THEME.md and design-qa.md for evidence and limitations. No hardware or live-account operation should be inferred from fixture screenshots.
